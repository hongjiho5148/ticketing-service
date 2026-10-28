package com.ticketing.backend.queue;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final boolean releaseSlotOnPurchase;
    private final Counter enteredCounter;
    private final Counter releasedCounter;

    public QueueService(
            RedissonClient redissonClient,
            WaitingQueueRepository waitingQueueRepository,
            EventServiceClient eventServiceClient,
            @Value("${queue.active-capacity}") int activeCapacity,
            @Value("${queue.admission-interval-ms}") long admissionIntervalMs,
            @Value("${queue.release-slot-on-purchase:true}") boolean releaseSlotOnPurchase,
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
        touch(eventId, List.of(queueToken)); // the person is here now; they have to keep showing it (see touch)

        long rankNo = rankOf(waiting, queueToken);

        waitingQueueRepository.save(new WaitingQueue(userId, eventId, queueToken, rankNo));
        enteredCounter.increment();

        return new QueueEnterResponse(queueToken, rankNo, estimateWaitSeconds(rankNo));
    }

    public QueueStatusResponse status(String queueToken) {
        return statusOf(eventOf(queueToken), queueToken);
    }

    /**
     * What a browser without a stream asks every couple of seconds: the answer, and a note that this person is still
     * there. Someone whose browser stops asking (and has no stream open either) is taken out of the line after
     * queue.abandon-grace-ms, so the slot goes to someone who is still waiting.
     */
    public QueueStatusResponse poll(String queueToken) {
        Long eventId = eventOf(queueToken);
        QueueStatusResponse status = statusOf(eventId, queueToken);
        if ("WAITING".equals(status.status())) {
            touch(eventId, List.of(queueToken));
        }
        return status;
    }

    /**
     * Notes that these waiting people are still here, as of now. One entry per person in a sorted set whose score is the
     * time, so the sweep can pick out everyone not heard from since a given moment with one range query. Entries are
     * removed when the person is let in or taken out of the line.
     */
    void touch(Long eventId, Collection<String> queueTokens) {
        if (queueTokens.isEmpty()) {
            return;
        }
        double now = System.currentTimeMillis();
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
            return QueueStatusResponse.passed(queueToken);
        }

        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(waitingKey(eventId));
        long rankNo = rankOf(waiting, queueToken);
        if (rankNo == 0) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        return QueueStatusResponse.waiting(rankNo, estimateWaitSeconds(rankNo));
    }

    /** The event a queue token belongs to. */
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
     * Hands back the active slot(s) a buyer is holding for an event, once they have paid: the slot would otherwise stay
     * taken until the pass token expired (ten minutes by default) although the person is finished. Looked up by user
     * and event because the live queue is keyed by queue token, which the payment side doesn't know. Safe to call
     * repeatedly - a slot that is already gone is simply not there to remove.
     *
     * <p>Deliberately does not filter on the history row's status: only tokens in the active set are touched anyway, so
     * looking at every entry of the user is safe, and rows admitted before QueueAdmissionSweeper started saving PASSED
     * are still WAITING.
     *
     * <p>The trade-off: the pass token stops working, so someone who wants more seats after paying joins the queue
     * again. Turn it off with queue.release-slot-on-purchase=false.
     *
     * @return how many slots were handed back
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

    /** When each waiting person was last known to be there (see touch). */
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
