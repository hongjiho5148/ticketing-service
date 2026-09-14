package com.ticketing.backend.queue;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import java.time.Duration;
import java.util.UUID;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis holds the live queue state (who's waiting, who's been let through); WaitingQueue rows are
 * just a history/stats trail. QueueAdmissionSweeper is the other half of this - it moves people
 * from "waiting" to "active" on a timer.
 */
@Service
public class QueueService {

    private final RedissonClient redissonClient;
    private final WaitingQueueRepository waitingQueueRepository;
    private final EventServiceClient eventServiceClient;
    private final int activeCapacity;
    private final long admissionIntervalMs;

    public QueueService(
            RedissonClient redissonClient,
            WaitingQueueRepository waitingQueueRepository,
            EventServiceClient eventServiceClient,
            @Value("${queue.active-capacity}") int activeCapacity,
            @Value("${queue.admission-interval-ms}") long admissionIntervalMs) {
        this.redissonClient = redissonClient;
        this.waitingQueueRepository = waitingQueueRepository;
        this.eventServiceClient = eventServiceClient;
        this.activeCapacity = activeCapacity;
        this.admissionIntervalMs = admissionIntervalMs;
    }

    @Transactional
    public QueueEnterResponse enter(Long userId, Long eventId) {
        eventServiceClient.getEvent(eventId); // throws EVENT_NOT_FOUND (404) if it doesn't exist

        String queueToken = UUID.randomUUID().toString();

        RAtomicLong sequence = redissonClient.getAtomicLong(sequenceKey(eventId));
        double score = sequence.incrementAndGet();

        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(waitingKey(eventId));
        waiting.add(score, queueToken);

        RBucket<Long> tokenEvent = redissonClient.getBucket(tokenEventKey(queueToken));
        tokenEvent.set(eventId, Duration.ofHours(1));

        long rankNo = rankOf(waiting, queueToken);

        waitingQueueRepository.save(new WaitingQueue(userId, eventId, queueToken, rankNo));

        return new QueueEnterResponse(queueToken, rankNo, estimateWaitSeconds(rankNo));
    }

    public QueueStatusResponse status(String queueToken) {
        Long eventId = redissonClient.<Long>getBucket(tokenEventKey(queueToken)).get();
        if (eventId == null) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }

        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(activeKey(eventId));
        Double activeExpiresAt = active.getScore(queueToken);
        if (activeExpiresAt != null && activeExpiresAt > System.currentTimeMillis()) {
            return QueueStatusResponse.passed(queueToken);
        }

        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(waitingKey(eventId));
        long rankNo = rankOf(waiting, queueToken);
        if (rankNo == 0) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        return QueueStatusResponse.waiting(rankNo, estimateWaitSeconds(rankNo));
    }

    public boolean isPassTokenValid(Long eventId, String passToken) {
        if (passToken == null || passToken.isBlank()) {
            return false;
        }
        Double expiresAt = redissonClient.<String>getScoredSortedSet(activeKey(eventId)).getScore(passToken);
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    private long rankOf(RScoredSortedSet<String> waiting, String queueToken) {
        Integer rank = waiting.rank(queueToken);
        return rank == null ? 0 : rank + 1L;
    }

    private long estimateWaitSeconds(long rankNo) {
        long batches = (rankNo + activeCapacity - 1) / activeCapacity;
        return batches * (admissionIntervalMs / 1000);
    }

    static String waitingKey(Long eventId) {
        return "queue:waiting:" + eventId;
    }

    static String activeKey(Long eventId) {
        return "queue:active:" + eventId;
    }

    private String sequenceKey(Long eventId) {
        return "queue:seq:" + eventId;
    }

    private String tokenEventKey(String queueToken) {
        return "queue:event:" + queueToken;
    }
}
