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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
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

    /** 슬롯이 비는 시각을 공연마다 이만큼 보관한다(slotsOf 참고). */
    static final long SLOTS_CACHE_MS = 1000;

    private record SlotsSnapshot(long readAtMs, long[] expiresAt) {
    }

    private final ConcurrentHashMap<Long, SlotsSnapshot> slotsCache = new ConcurrentHashMap<>();

    private final RedissonClient redissonClient;
    private final WaitingQueueRepository waitingQueueRepository;
    private final EventServiceClient eventServiceClient;
    private final int activeCapacity;
    private final long admissionIntervalMs;
    private final long passTokenTtlMs;
    private final boolean releaseSlotOnPurchase;
    private final long passExtensionMs;
    private final long pollCreditMs;
    private final Counter extendedCounter;
    private final Counter enteredCounter;
    private final Counter reenteredCounter;
    private final Counter droppedPassCounter;
    private final Counter releasedCounter;

    public QueueService(
            RedissonClient redissonClient,
            WaitingQueueRepository waitingQueueRepository,
            EventServiceClient eventServiceClient,
            @Value("${queue.active-capacity}") int activeCapacity,
            @Value("${queue.admission-interval-ms}") long admissionIntervalMs,
            @Value("${queue.pass-token-ttl-ms:300000}") long passTokenTtlMs,
            @Value("${queue.release-slot-on-purchase:true}") boolean releaseSlotOnPurchase,
            @Value("${queue.pass-extension-ms:300000}") long passExtensionMs,
            @Value("${queue.poll-credit-ms:120000}") long pollCreditMs,
            MeterRegistry meterRegistry) {
        this.enteredCounter = Counter.builder("queue_entered_total").description("People who joined a queue").register(meterRegistry);
        this.reenteredCounter = Counter.builder("queue_reentered_total")
                .description("Entries answered with the waiting place the person already had, instead of a new one").register(meterRegistry);
        this.droppedPassCounter = Counter.builder("queue_passes_dropped_total")
                .description("Passes given up because their holder entered the line again (they stand at the back)").register(meterRegistry);
        this.releasedCounter = Counter.builder("queue_slots_released_total")
                .description("Active slots handed back because the buyer paid").register(meterRegistry);
        this.redissonClient = redissonClient;
        this.waitingQueueRepository = waitingQueueRepository;
        this.eventServiceClient = eventServiceClient;
        this.activeCapacity = activeCapacity;
        this.admissionIntervalMs = admissionIntervalMs;
        this.passTokenTtlMs = passTokenTtlMs;
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

        // 사용자·공연당 자리는 하나다. 새로고침, 탭 여러 개, 개발 모드의 이중 실행이 같은 사람을 줄에 여러 번 세우지 못하게 한다.
        // 확인하고 만드는 사이에 같은 사람의 요청이 끼어들 수 없도록 사용자·공연별 잠금 안에서 한다.
        RLock lock = redissonClient.getLock(enterLockKey(eventId, userId));
        boolean locked;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.QUEUE_BUSY);
        }
        if (!locked) {
            throw new ApiException(ErrorCode.QUEUE_BUSY);
        }
        try {
            RBucket<String> place = redissonClient.getBucket(placeKey(eventId, userId));
            String existing = place.get();
            if (existing != null) {
                QueueEnterResponse again = existingPlace(eventId, existing);
                if (again != null) {
                    reenteredCounter.increment();
                    return again;
                }
                // 그 자리는 이미 없다(입장권이 끝났거나 결제로 슬롯을 돌려줬거나 줄에서 빠졌다). 새로 선다.
            }

            String queueToken = UUID.randomUUID().toString();

            RAtomicLong sequence = redissonClient.getAtomicLong(sequenceKey(eventId));
            double score = sequence.incrementAndGet();

            RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(waitingKey(eventId));
            waiting.add(score, queueToken);

            RBucket<Long> tokenEvent = redissonClient.getBucket(tokenEventKey(queueToken));
            tokenEvent.set(eventId, Duration.ofHours(1));
            place.set(queueToken, Duration.ofHours(1));
            touch(eventId, List.of(queueToken)); // 이 사람은 지금 여기 있다. 계속 그것을 보여 줘야 한다(touch 참고)

            long rankNo = rankOf(waiting, queueToken);

            waitingQueueRepository.save(new WaitingQueue(userId, eventId, queueToken, rankNo));
            enteredCounter.increment();

            return new QueueEnterResponse(queueToken, rankNo, estimateWaitSeconds(rankNo, slotsOf(eventId), System.currentTimeMillis()));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 이 사람이 아직 줄에서 기다리는 중이면 그 자리(지금 순번)를 돌려준다. 새로고침이나 탭을 하나 더 열어도 줄 맨 뒤로 가지
     * 않는다. 이미 입장해 있던 사람이 다시 들어오면 입장권을 포기한 것으로 보고 그 슬롯을 돌려준 뒤 null을 돌려주어 맨 뒤에
     * 새로 서게 한다(입장해서 새로고침한 사람이 입장권도 쥐고 줄 앞자리도 지키는 일은 없다). 그 자리가 이미 없어도 null이다.
     */
    private QueueEnterResponse existingPlace(Long eventId, String queueToken) {
        // 대기 목록을 먼저 본다(입장 처리의 쓰기 순서와 반대로 읽는 이유는 statusOf 참고). 입장 목록을 먼저 보면 입장하는 순간의
        // 사람을 "둘 다 없음"으로 보고, 입장권을 쥔 채 새로 서는 사람이 생긴다.
        long rankNo = rankOf(redissonClient.getScoredSortedSet(waitingKey(eventId)), queueToken);
        if (rankNo > 0) {
            touch(eventId, List.of(queueToken)); // 돌아온 사람이다. 아직 여기 있다
            return new QueueEnterResponse(queueToken, rankNo, estimateWaitSeconds(rankNo, slotsOf(eventId), System.currentTimeMillis()));
        }
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(activeKey(eventId));
        if (active.remove(queueToken)) {
            droppedPassCounter.increment();
            invalidateSlots(eventId);
        }
        return null;
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
                // 대기 목록을 먼저 읽는다. 이유는 statusOf 참고(입장 처리의 쓰기 순서와 반대로 읽어야 틈이 없다).
                batch.<String>getScoredSortedSet(waitingKey(eventId)).rankAsync(token);
                batch.<String>getScoredSortedSet(activeKey(eventId)).getScoreAsync(token);
            }
            List<?> responses = batch.execute().getResponses();
            long now = System.currentTimeMillis();
            for (int i = 0; i < chunk.size(); i++) {
                String token = chunk.get(i);
                Integer rank = (Integer) responses.get(2 * i);
                Double activeExpiresAt = (Double) responses.get(2 * i + 1);
                if (activeExpiresAt != null && activeExpiresAt > now) {
                    result.put(token, QueueStatusResponse.passed(token, secondsUntil(activeExpiresAt)));
                } else if (rank != null) {
                    long[] slots = slotsOf(eventByToken.get(token)); // 공연마다 짧게 보관하므로 사람마다 Redis를 읽지 않는다
                    result.put(token, QueueStatusResponse.waiting(rank + 1L, estimateWaitSeconds(rank + 1L, slots, now)));
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
        // 입장 처리는 "입장 목록에 넣고, 그다음 대기 목록에서 뺀다"의 두 단계다(QueueAdmissionSweeper). 읽는 순서는 그 반대로,
        // 대기 목록을 먼저 본다. 입장 목록을 먼저 보면 "아직 없음"을 본 직후에 입장 처리가 두 단계를 모두 끝낼 수 있고, 그러면 대기
        // 목록에서도 찾지 못해 방금 입장한 사람이 줄에서 빠진 것으로 보인다. 대기 목록에 없다고 본 때는 입장 목록 쪽 쓰기가 이미
        // 끝나 있으므로 이 순서에서는 그런 틈이 없다.
        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(waitingKey(eventId));
        long rankNo = rankOf(waiting, queueToken);

        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(activeKey(eventId));
        Double activeExpiresAt = active.getScore(queueToken);
        if (activeExpiresAt != null && activeExpiresAt > System.currentTimeMillis()) {
            return QueueStatusResponse.passed(queueToken, secondsUntil(activeExpiresAt));
        }

        if (rankNo == 0) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        return QueueStatusResponse.waiting(rankNo, estimateWaitSeconds(rankNo, slotsOf(eventId), System.currentTimeMillis()));
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
        if (released > 0) {
            invalidateSlots(eventId);
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

    /**
     * 슬롯마다 언제 비는지(끝나는 시각, 밀리초)를 오름차순으로 돌려준다. 비어 있는 슬롯은 0이고, 크기는 정원이다. 예상 대기시간을
     * 계산하는 데 쓰고, 사람마다 읽으면 비싸서 공연마다 {@value #SLOTS_CACHE_MS}ms 동안 보관한다(몇 초 단위 추정이라 그만큼
     * 오래돼도 상관없다). 입장하거나 슬롯을 돌려주면 바로 버린다.
     */
    long[] slotsOf(Long eventId) {
        long now = System.currentTimeMillis();
        SlotsSnapshot cached = slotsCache.get(eventId);
        if (cached != null && now - cached.readAtMs < SLOTS_CACHE_MS) {
            return cached.expiresAt;
        }
        // 한꺼번에 몰릴 때 보관이 끝난 순간 모든 요청이 같이 읽지 않도록, 한 요청만 읽고 나머지는 그 결과를 쓴다.
        return slotsCache.compute(eventId, (key, old) -> {
            long readAt = System.currentTimeMillis();
            if (old != null && readAt - old.readAtMs < SLOTS_CACHE_MS) {
                return old;
            }
            return new SlotsSnapshot(readAt, readSlots(eventId, readAt));
        }).expiresAt;
    }

    private long[] readSlots(Long eventId, long now) {
        List<Double> live = new ArrayList<>();
        for (var entry : redissonClient.<String>getScoredSortedSet(activeKey(eventId)).entryRange(0, -1)) {
            if (entry.getScore() > now) {
                live.add(entry.getScore());
            }
        }
        long[] slots = new long[Math.max(activeCapacity, live.size())];
        int free = slots.length - live.size();
        for (int i = 0; i < live.size(); i++) {
            slots[free + i] = live.get(i).longValue(); // 정렬된 채로 온다
        }
        return slots;
    }

    void invalidateSlots(Long eventId) {
        slotsCache.remove(eventId);
    }

    /**
     * 내 앞에 몇 명이 있는지와 지금 입장해 있는 사람들의 입장권이 언제 끝나는지로 추정한 대기시간이다. 슬롯은 정원만큼 있고,
     * 각자 비는 때가 다르다(비어 있으면 지금). 줄 앞에서부터 가장 먼저 비는 슬롯을 하나씩 받는다고 보고, 그 슬롯을 받은 사람의
     * 입장권도 입장권 수명만큼 쥔다고 본다. 그러면 순번 r의 사람은 슬롯 (r-1)%정원 번째가 비는 때에, 정원만큼 한 바퀴 돌 때마다
     * 입장권 수명을 더해서 들어간다. 입장한 사람들이 입장권을 끝까지 쥔다는 가정이라(구매하며 일찍 돌려주면 더 빠르고, 좌석을
     * 잡아 입장권이 연장되면 더 느리다) 근사치다. 입장권 5분이 측정한 값이 아니라 시작값이므로 이 값도 그렇다
     * (AdmissionUseTracker가 모으는 입장 뒤 첫 예약까지의 시간으로 바꿀 수 있다).
     *
     * @param slotExpiresAt {@link #slotsOf}가 돌려준 값
     */
    long estimateWaitSeconds(long rankNo, long[] slotExpiresAt, long nowMs) {
        long tickSeconds = Math.max(1, (admissionIntervalMs + 999) / 1000);
        if (slotExpiresAt.length == 0) {
            return Math.max(tickSeconds, passTokenTtlMs / 1000);
        }
        // 순번 0은 줄에 선 직후 순번을 읽기 전에 이미 입장 처리된 사람이다(정원에 여유가 있으면 0.3초 주기를 기다릴 필요도 없이
        // 곧바로 입장한다). 가장 앞 순번과 같게 다룬다. 이 처리가 없으면 아래 나머지 계산이 -1이 되어 배열 범위를 벗어난다.
        long position = Math.max(1, rankNo);
        int index = (int) ((position - 1) % slotExpiresAt.length);
        long rounds = (position - 1) / slotExpiresAt.length;
        long untilMs = Math.max(0, slotExpiresAt[index] - nowMs) + rounds * passTokenTtlMs;
        // 이미 비어 있는 슬롯을 받을 때만 입장 주기(다음 입장 시각)가 걸린다. 자리가 나기를 기다리는 사람에게는 상관없다.
        return untilMs <= 0 ? tickSeconds : (untilMs + 999) / 1000;
    }

    /** 사용자·공연당 줄 자리의 대기열 토큰을 가리키는 키다. 같은 사람이 여러 번 서지 못하게 한다. */
    private String placeKey(Long eventId, Long userId) {
        return "queue:place:" + eventId + ":" + userId;
    }

    private String enterLockKey(Long eventId, Long userId) {
        return "queue:enter-lock:" + eventId + ":" + userId;
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
