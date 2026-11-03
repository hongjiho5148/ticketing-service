package com.ticketing.backend.queue;

import java.time.LocalDateTime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBatch;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적으로 다음 대기자 묶음을 들여보낸다. 빈 활성 슬롯 수만큼이다.
 *
 * <p>Redis 잠금으로 보호한다. backend를 여러 인스턴스로 늘렸을 때 주어진 스윕 주기를 인스턴스 하나만 돌리게
 * 하기 위해서다. 그렇지 않으면 모든 인스턴스가 같은 대기 집합에서 자기만의 묶음을 들여보내서, 활성 용량만큼이
 * 아니라 (복제본 수 x 활성 용량)명까지 통과시킨다.
 */
@Component
public class QueueAdmissionSweeper {

    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionSweeper.class);

    private final RedissonClient redissonClient;
    private final WaitingQueueRepository waitingQueueRepository;
    private final int activeCapacity;
    private final long passTokenTtlMs;
    private final long abandonGraceMs;
    private final long sweepLockLeaseMs;
    private final QueueStreams queueStreams;
    private final Counter admittedCounter;
    private final Counter abandonedCounter;

    public QueueAdmissionSweeper(
            RedissonClient redissonClient,
            WaitingQueueRepository waitingQueueRepository,
            @Value("${queue.active-capacity}") int activeCapacity,
            @Value("${queue.pass-token-ttl-ms}") long passTokenTtlMs,
            @Value("${queue.admission-interval-ms}") long admissionIntervalMs,
            @Value("${queue.abandon-grace-ms:60000}") long abandonGraceMs,
            QueueStreams queueStreams,
            MeterRegistry meterRegistry) {
        this.queueStreams = queueStreams;
        this.abandonGraceMs = abandonGraceMs;
        this.abandonedCounter = Counter.builder("queue_abandoned_total")
                .description("Waiting people taken out of the line because they stopped showing up").register(meterRegistry);
        this.admittedCounter = Counter.builder("queue_admitted_total").description("People let in from the queue").register(meterRegistry);
        this.redissonClient = redissonClient;
        this.waitingQueueRepository = waitingQueueRepository;
        this.activeCapacity = activeCapacity;
        this.passTokenTtlMs = passTokenTtlMs;
        // 주기 간격과 별개로(하한을 두고) 정한다. 그래서 DB 부하가 큰 상황에서 느린 스윕이 자기 임대보다 오래
        // 가지 않는다. 그런 일은 아래 finally 블록의 잠금 해제와 경합해서 5000-VU 스케일 아웃 부하 테스트에서
        // IllegalMonitorStateException으로 나타났다.
        this.sweepLockLeaseMs = Math.max(admissionIntervalMs * 4, 2000);
    }

    // 첫 실행도 이후의 모든 실행처럼 한 주기를 기다린다. 서비스가 막 시작된 순간에는 줄 선 사람이 없고, 시작 직후
    // 백그라운드에서 스윕이 돌기 시작하면 호출한 쪽이 곧 살펴볼 사람들을 들여보내 버린다(컨텍스트가 시작된 직후
    // 사람들을 줄 세우는 테스트가 가끔 실패하게 만들었다).
    @Scheduled(initialDelayString = "${queue.admission-interval-ms}", fixedRateString = "${queue.admission-interval-ms}")
    public void admitWaitingUsers() {
        RLock lock = redissonClient.getLock("lock:queue-sweep");
        boolean acquired;
        try {
            acquired = lock.tryLock(0, sweepLockLeaseMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            return; // 다른 인스턴스가 이미 이번 주기를 돌리고 있다
        }
        try {
            for (String key : redissonClient.getKeys().getKeysByPattern("queue:waiting:*")) {
                Long eventId = Long.valueOf(key.substring(key.lastIndexOf(':') + 1));
                admitForEvent(eventId);
            }
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (IllegalMonitorStateException e) {
                // 임대가 이미 끝나서 잠금이 다른 곳에 다시 배정됐다. 풀 것이 없다.
            }
        }
    }

    /**
     * 유예 시간 동안 소식이 없는 사람들을 줄에서 뺀다. 그 사람들의 브라우저는 스트림을 열어 두지도, 상태를 묻지도 않는다.
     * 이것이 없으면 그들은 줄에 남아 있다가 차례가 오면 입장해서 입장권 수명 내내 슬롯을 차지하고, 실제로 기다리는
     * 사람은 그 뒤에 남게 된다. 유예가 0 이하이면 아무것도 하지 않는다. 줄에서 빠진 사람이 돌아오면 자기 토큰이 사라진 것을
     * 보고 맨 뒤에 다시 선다.
     */
    void removeAbandoned(Long eventId) {
        if (abandonGraceMs <= 0) {
            return;
        }
        RScoredSortedSet<String> seen = redissonClient.getScoredSortedSet(QueueService.seenKey(eventId));
        Collection<String> stale = seen.valueRange(
                Double.NEGATIVE_INFINITY, true, System.currentTimeMillis() - abandonGraceMs, true, 0, 1000);
        if (stale.isEmpty()) {
            return;
        }
        // 실제로 아직 줄에 있던 사람을 기준으로 센다. 방금 입장한 사람은 여기에 오래된 기록이 아직 남아 있을 수 있다.
        RBatch batch = redissonClient.createBatch();
        for (String token : stale) {
            batch.getScoredSortedSet(QueueService.waitingKey(eventId)).removeAsync(token);
        }
        long removed = batch.execute().getResponses().stream().filter(Boolean.TRUE::equals).count();
        seen.removeAll(stale);
        if (removed == 0) {
            return;
        }
        abandonedCounter.increment(removed);
        log.info("took {} people who stopped showing up out of the line of event {}", removed, eventId);
        try {
            waitingQueueRepository.markAbandoned(stale);
        } catch (RuntimeException e) {
            log.warn("could not mark {} abandoned entries of event {}", stale.size(), eventId, e);
        }
    }

    void admitForEvent(Long eventId) {
        removeAbandoned(eventId);
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(QueueService.activeKey(eventId));
        long now = System.currentTimeMillis();
        active.removeRangeByScore(0, true, now, true);

        int freeSlots = activeCapacity - active.size();
        if (freeSlots <= 0) {
            return;
        }

        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(QueueService.waitingKey(eventId));
        Collection<String> toAdmit = waiting.valueRange(0, freeSlots - 1);
        if (toAdmit.isEmpty()) {
            return;
        }

        long expiresAt = now + passTokenTtlMs;
        for (String queueToken : toAdmit) {
            active.add(expiresAt, queueToken);
            waiting.remove(queueToken);
        }
        admittedCounter.increment(toAdmit.size());
        redissonClient.getScoredSortedSet(QueueService.seenKey(eventId)).removeAll(toAdmit);
        // 어느 인스턴스에서든 기다리는 연결들에 알린다. 여기서 실패하면 그들이 다음 갱신 때 알게 될 뿐이다.
        try {
            queueStreams.announceAdmitted(eventId, expiresAt, toAdmit);
        } catch (RuntimeException e) {
            log.warn("could not announce {} admitted entries of event {}", toAdmit.size(), eventId, e);
        }
        // 이 클래스의 @Transactional 메서드가 아니다. admitWaitingUsers()가 같은 객체에서 호출하므로 Spring 프록시가
        // 가로채지 못해서 아무것도 저장되지 않는다. 리포지토리 호출이 자기 트랜잭션을 열고, 묶음 전체에 대해 UPDATE 한 번이다.
        // 이력은 통계일 뿐이다. DB가 잠깐 흔들려도 이미 Redis에서 입장시킨 사람은 그대로 들어가 있고 다른 공연은 계속 진행된다.
        try {
            waitingQueueRepository.markPassed(toAdmit, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("could not mark {} admitted entries of event {} PASSED", toAdmit.size(), eventId, e);
        }
    }
}
