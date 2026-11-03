package com.ticketing.backend.queue;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.eventclient.dto.EventExistsResponse;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis는 실시간 대기열 상태(누가 기다리고, 누가 통과했는지)를 담고, WaitingQueue 행은 이력/통계 기록일 뿐이다.
 * QueueAdmissionSweeper가 이것의 나머지 절반이다. 타이머로 사람들을 "대기"에서 "활성"으로 옮긴다.
 */
@Service
public class QueueService {

    /** statusOfMany에서 왕복 한 번에 읽는 사람 수다(사람마다 명령 두 개). */
    private static final int STATUS_BATCH = 500;

    private final RedissonClient redissonClient;
    private final WaitingQueueRepository waitingQueueRepository;
    private final EventServiceClient eventServiceClient;
    private final int activeCapacity;
    private final long admissionIntervalMs;
    private final boolean releaseSlotOnPurchase;
    private final long passExtensionMs;
    private final long pollCreditMs;
    private final Counter extendedCounter;
    private final Counter enteredCounter;
    private final Counter releasedCounter;

    public QueueService(
            RedissonClient redissonClient,
            WaitingQueueRepository waitingQueueRepository,
            EventServiceClient eventServiceClient,
            @Value("${queue.active-capacity}") int activeCapacity,
            @Value("${queue.admission-interval-ms}") long admissionIntervalMs,
            @Value("${queue.release-slot-on-purchase:true}") boolean releaseSlotOnPurchase,
            @Value("${queue.pass-extension-ms:300000}") long passExtensionMs,
            @Value("${queue.poll-credit-ms:120000}") long pollCreditMs,
            MeterRegistry meterRegistry) {
        this.enteredCounter = Counter.builder("queue_entered_total").description("People who joined a queue").register(meterRegistry);
        this.releasedCounter = Counter.builder("queue_slots_released_total")
                .description("Active slots handed back because the buyer paid").register(meterRegistry);
        this.redissonClient = redissonClient;
        this.waitingQueueRepository = waitingQueueRepository;
        this.eventServiceClient = eventServiceClient;
        this.activeCapacity = activeCapacity;
        this.admissionIntervalMs = admissionIntervalMs;
        this.releaseSlotOnPurchase = releaseSlotOnPurchase;
        this.passExtensionMs = passExtensionMs;
        this.pollCreditMs = pollCreditMs;
        this.extendedCounter = Counter.builder("queue_passes_extended_total")
                .description("Passes lengthened because their holder took a seat").register(meterRegistry);
    }

    /**
     * 일부러 @Transactional로 하지 않았다. 공연 확인은 다른 서비스 호출이고 대기열 상태는 Redis에 있는데, 메서드를 감싼
     * 트랜잭션이 그 호출 내내 데이터베이스 커넥션을 붙잡고 있었다. 몇천 명이 한꺼번에 들어오면 MySQL 자체는 놀고 있는데도
     * 풀의 모든 커넥션이 event-service를 기다리느라 바빴다. 데이터베이스가 필요한 것은 이력 행뿐이고, 그것을 저장할 때는
     * 자기 짧은 트랜잭션을 연다.
     */
    public QueueEnterResponse enter(Long userId, Long eventId) {
        EventExistsResponse event = eventServiceClient.getEvent(eventId); // 존재하지 않으면 EVENT_NOT_FOUND(404)를 던진다
        if (event != null && event.salesClosed()) {
            throw new ApiException(ErrorCode.SALES_CLOSED); // 더는 아무도 예매할 수 없는 공연을 위해 줄 서 있을 이유가 없다
        }

        String queueToken = UUID.randomUUID().toString();

        RAtomicLong sequence = redissonClient.getAtomicLong(sequenceKey(eventId));
        double score = sequence.incrementAndGet();

        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(waitingKey(eventId));
        waiting.add(score, queueToken);

        RBucket<Long> tokenEvent = redissonClient.getBucket(tokenEventKey(queueToken));
        tokenEvent.set(eventId, Duration.ofHours(1));
        touch(eventId, List.of(queueToken)); // 이 사람은 지금 여기 있다. 계속 그것을 보여 줘야 한다(touch 참고)

        long rankNo = rankOf(waiting, queueToken);

        waitingQueueRepository.save(new WaitingQueue(userId, eventId, queueToken, rankNo));
        enteredCounter.increment();

        return new QueueEnterResponse(queueToken, rankNo, estimateWaitSeconds(rankNo));
    }

    public QueueStatusResponse status(String queueToken) {
        return statusOf(eventOf(queueToken), queueToken);
    }

    /**
     * 스트림이 없는 브라우저가 몇 초마다 묻는 것이다. 답과, 이 사람이 아직 있다는 표시를 함께 남긴다. 브라우저가 더는
     * 묻지 않고(스트림도 열려 있지 않은) 사람은 queue.abandon-grace-ms 뒤에 줄에서 빠지고, 그 슬롯은 여전히 기다리는
     * 사람에게 간다.
     */
    public QueueStatusResponse poll(String queueToken) {
        Long eventId = eventOf(queueToken);
        QueueStatusResponse status = statusOf(eventId, queueToken);
        if ("WAITING".equals(status.status())) {
            touch(eventId, List.of(queueToken), pollCreditMs);
        }
        return status;
    }

    /**
     * 이 사람들이 각각 어디에 서 있는지를, 사람마다 세 번이 아니라 몇 번의 왕복으로 읽는다. 모든 연결의 상태를
     * 타이머로 다시 읽는데, 하나씩 읽으면 연결이 몇천 개일 때 몇 초가 걸렸다. 입장하지도 않았고 더는 기다리지도 않는
     * 토큰은 결과에 없다.
     *
     * @param eventByToken 각 사람의 대기열 토큰과 그 토큰이 속한 공연
     */
    Map<String, QueueStatusResponse> statusOfMany(Map<String, Long> eventByToken) {
        Map<String, QueueStatusResponse> result = new HashMap<>();
        List<String> tokens = new ArrayList<>(eventByToken.keySet());
        for (int from = 0; from < tokens.size(); from += STATUS_BATCH) {
            List<String> chunk = tokens.subList(from, Math.min(from + STATUS_BATCH, tokens.size()));
            RBatch batch = redissonClient.createBatch();
            for (String token : chunk) {
                Long eventId = eventByToken.get(token);
                batch.<String>getScoredSortedSet(activeKey(eventId)).getScoreAsync(token);
                batch.<String>getScoredSortedSet(waitingKey(eventId)).rankAsync(token);
            }
            List<?> responses = batch.execute().getResponses();
            long now = System.currentTimeMillis();
            for (int i = 0; i < chunk.size(); i++) {
                String token = chunk.get(i);
                Double activeExpiresAt = (Double) responses.get(2 * i);
                Integer rank = (Integer) responses.get(2 * i + 1);
                if (activeExpiresAt != null && activeExpiresAt > now) {
                    result.put(token, QueueStatusResponse.passed(token, secondsUntil(activeExpiresAt)));
                } else if (rank != null) {
                    result.put(token, QueueStatusResponse.waiting(rank + 1L, estimateWaitSeconds(rank + 1L)));
                }
            }
        }
        return result;
    }

    /**
     * 기다리는 이 사람들이 지금 시점에도 여기 있다고 표시한다. 점수가 시각인 정렬 집합에서 사람마다 항목 하나이므로, 스윕이
     * 특정 시점 이후로 소식이 없는 모든 사람을 범위 쿼리 한 번으로 골라낼 수 있다. 항목은 사람이 입장하거나 줄에서
     * 빠질 때 지운다.
     */
    void touch(Long eventId, Collection<String> queueTokens) {
        touch(eventId, queueTokens, 0);
    }

    /**
     * @param creditMs 표시에 더하는 시간이다. 그만큼 더 오래 있는 것으로 센다. 상태만 묻는 사람(스트림이 없음)에게는
     *     몇 분을 얹어 준다. 브라우저가 백그라운드 탭의 타이머를 늦춰서 1분 가까이 다시 묻지 않을 수 있기 때문이다.
     *     열린 연결에는 얹어 줄 필요가 없다. 몇 초마다 갱신되고 죽으면 바로 알아챈다.
     */
    void touch(Long eventId, Collection<String> queueTokens, long creditMs) {
        if (queueTokens.isEmpty()) {
            return;
        }
        double now = System.currentTimeMillis() + creditMs;
        Map<String, Double> seen = new HashMap<>();
        for (String token : queueTokens) {
            seen.put(token, now);
        }
        redissonClient.<String>getScoredSortedSet(seenKey(eventId)).addAll(seen);
    }

    private QueueStatusResponse statusOf(Long eventId, String queueToken) {
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(activeKey(eventId));
        Double activeExpiresAt = active.getScore(queueToken);
        if (activeExpiresAt != null && activeExpiresAt > System.currentTimeMillis()) {
            return QueueStatusResponse.passed(queueToken, secondsUntil(activeExpiresAt));
        }

        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(waitingKey(eventId));
        long rankNo = rankOf(waiting, queueToken);
        if (rankNo == 0) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        return QueueStatusResponse.waiting(rankNo, estimateWaitSeconds(rankNo));
    }

    /** 대기열 토큰이 속한 공연. */
    Long eventOf(String queueToken) {
        Long eventId = redissonClient.<Long>getBucket(tokenEventKey(queueToken)).get();
        if (eventId == null) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        return eventId;
    }

    public boolean isPassTokenValid(Long eventId, String passToken) {
        if (passToken == null || passToken.isBlank()) {
            return false;
        }
        Double expiresAt = redissonClient.<String>getScoredSortedSet(activeKey(eventId)).getScore(passToken);
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    /**
     * 구매자가 결제를 마쳤을 때 그 사람이 공연에서 쥐고 있는 활성 슬롯을 돌려준다. 그렇지 않으면 사람은 이미 끝났는데도
     * 입장권 토큰이 만료될 때까지(기본 10분) 슬롯이 그대로 차 있다. 실시간 대기열이 대기열 토큰을 키로 하는데 결제 쪽은 그것을
     * 모르므로 사용자와 공연으로 찾는다. 반복 호출해도 안전하다. 이미 없어진 슬롯은 지울 것이 없을 뿐이다.
     *
     * <p>이력 행의 상태로는 일부러 거르지 않는다. 어차피 활성 집합에 있는 토큰만 건드리므로 사용자의 모든 항목을 보는 것이
     * 안전하고, QueueAdmissionSweeper가 PASSED를 저장하기 시작하기 전에 입장한 행은 여전히 WAITING이다.
     *
     * <p>트레이드오프: 입장권 토큰이 더는 동작하지 않으므로, 결제한 뒤에 좌석을 더 사려는 사람은 대기열에 다시 선다.
     * queue.release-slot-on-purchase=false로 끌 수 있다.
     *
     * @return 돌려준 슬롯 수
     */
    @Transactional(readOnly = true)
    public int releaseSlotsOfBuyer(Long userId, Long eventId) {
        if (!releaseSlotOnPurchase) {
            return 0;
        }
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(activeKey(eventId));
        int released = 0;
        for (WaitingQueue entry : waitingQueueRepository.findByUserIdAndEventId(userId, eventId)) {
            if (active.remove(entry.getQueueToken())) {
                released++;
                releasedCounter.increment();
            }
        }
        return released;
    }

    /**
     * 방금 좌석을 잡은 사람의 입장권을 지금부터 최소 pass-extension-ms만큼 연장한다. 입장권은 짧게 유지해서, 입장하고
     * 아무것도 하지 않은 사람이 슬롯을 곧 돌려주게 한다. 이것은 실제로 구매 중인 사람이 중간에 입장권을 잃지 않게 하는
     * 장치다(다른 좌석을 고르거나, 계속해서 결제해야 하면 결제를 마칠 때). 아직 유효한 입장권만 건드리고 절대 줄이지
     * 않는다. 이미 끝난 입장권은 끝난 채로 둔다. 반복 호출해도 안전하다(두 번째 좌석, 다시 전달된 이벤트).
     *
     * @return 연장한 입장권 수
     */
    @Transactional(readOnly = true)
    public int extendPassesOfHolder(Long userId, Long eventId) {
        if (passExtensionMs <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        double until = now + passExtensionMs;
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(activeKey(eventId));
        int extended = 0;
        for (WaitingQueue entry : waitingQueueRepository.findByUserIdAndEventId(userId, eventId)) {
            Double expiresAt = active.getScore(entry.getQueueToken());
            if (expiresAt != null && expiresAt > now && until > expiresAt) {
                active.add(until, entry.getQueueToken());
                extended++;
                extendedCounter.increment();
            }
        }
        return extended;
    }

    /** 지금부터 이 시각(밀리초)에 끝나는 입장권까지 남은 초를 정수로 돌려준다. 0 아래로 내려가지 않는다. */
    static long secondsUntil(double expiresAtMs) {
        return Math.max(0, (long) Math.ceil((expiresAtMs - System.currentTimeMillis()) / 1000.0));
    }

    private long rankOf(RScoredSortedSet<String> waiting, String queueToken) {
        Integer rank = waiting.rank(queueToken);
        return rank == null ? 0 : rank + 1L;
    }

    long estimateWaitSeconds(long rankNo) {
        long batches = (rankNo + activeCapacity - 1) / activeCapacity;
        return batches * (admissionIntervalMs / 1000);
    }

    static String waitingKey(Long eventId) {
        return "queue:waiting:" + eventId;
    }

    static String activeKey(Long eventId) {
        return "queue:active:" + eventId;
    }

    /** 기다리는 사람마다 마지막으로 있었다고 알려진 시각이다(touch 참고). */
    static String seenKey(Long eventId) {
        return "queue:seen:" + eventId;
    }

    private String sequenceKey(Long eventId) {
        return "queue:seq:" + eventId;
    }

    private String tokenEventKey(String queueToken) {
        return "queue:event:" + queueToken;
    }
}
