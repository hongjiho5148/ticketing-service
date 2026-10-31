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
 * Redis holds the live queue state (who's waiting, who's been let through); WaitingQueue rows are
 * just a history/stats trail. QueueAdmissionSweeper is the other half of this - it moves people
 * from "waiting" to "active" on a timer.
 */
@Service
public class QueueService {

    /** People read per round trip in statusOfMany (two commands each). */
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
     * Deliberately not @Transactional: the event check is a call to another service and the queue state is in Redis, so a
     * transaction around the method held a database connection for the whole of that call. When a few thousand people join
     * at once that kept every connection in the pool busy waiting on event-service while MySQL itself sat idle. Only the
     * history row needs the database, and saving it opens its own short transaction.
     */
    public QueueEnterResponse enter(Long userId, Long eventId) {
        EventExistsResponse event = eventServiceClient.getEvent(eventId); // throws EVENT_NOT_FOUND (404) if it doesn't exist
        if (event != null && event.salesClosed()) {
            throw new ApiException(ErrorCode.SALES_CLOSED); // nobody should wait in line for a show nobody can book any more
        }

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
            touch(eventId, List.of(queueToken), pollCreditMs);
        }
        return status;
    }

    /**
     * Where each of these people stands, read in a few round trips instead of three per person: every connection's state
     * is re-read on a timer, and one at a time that took seconds for a few thousand connections. A token that is neither
     * let in nor waiting any more is missing from the result.
     *
     * @param eventByToken each person's queue token and the event it belongs to
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
     * Notes that these waiting people are still here, as of now. One entry per person in a sorted set whose score is the
     * time, so the sweep can pick out everyone not heard from since a given moment with one range query. Entries are
     * removed when the person is let in or taken out of the line.
     */
    void touch(Long eventId, Collection<String> queueTokens) {
        touch(eventId, queueTokens, 0);
    }

    /**
     * @param creditMs time added to the note, so the person counts as present that much longer. Someone who is only
     *     asking for their status (no stream) is credited a couple of minutes because a browser slows the timers of a
     *     background tab and may not ask again for the better part of a minute; an open connection needs no credit,
     *     it is refreshed every few seconds and noticed at once when it dies.
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

    /**
     * Lengthens the pass of someone who has just taken a seat, to at least pass-extension-ms from now. Passes are kept
     * short so that a person who was let in and did nothing gives the slot back soon; this is what keeps a person who is
     * actually buying from losing theirs in the middle (to pick another seat or to finish paying, if they go on to need
     * it). Only a pass that is still valid is touched, and it is never shortened: a pass that has run out stays out.
     * Safe to call repeatedly (a second seat, a redelivered event).
     *
     * @return how many passes were lengthened
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

    /** Whole seconds from now until a pass that runs out at this clock time (milliseconds), never below zero. */
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
