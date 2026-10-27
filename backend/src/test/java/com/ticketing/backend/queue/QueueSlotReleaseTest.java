package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handing a buyer's slot back once they have paid. Against the compose Redis and MySQL (the database part rolled back;
 * the Redis keys of the test events are deleted afterwards). Capacity is 2 so a third person has to wait, and the
 * admission sweeper's own timer is parked so the test decides when people are let in. Run inside the compose network with
 * DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {"queue.active-capacity=2", "queue.admission-interval-ms=3600000"})
@Transactional
class QueueSlotReleaseTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private Long eventId;
    private final List<String> tokens = new ArrayList<>();

    @BeforeEach
    void setUp() {
        eventId = 7_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    }

    @AfterEach
    void cleanUpRedis() {
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId);
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t));
    }

    private String enter(long userId) {
        QueueEnterResponse response = queueService.enter(userId, eventId);
        tokens.add(response.queueToken());
        return response.queueToken();
    }

    private static long user() {
        return 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    }

    @Test
    void aBuyersSlotIsHandedBackSoTheNextWaiterIsLetIn() {
        long a = user();
        long b = user();
        long c = user();
        String tokenA = enter(a);
        String tokenB = enter(b);
        String tokenC = enter(c);
        sweeper.admitForEvent(eventId); // capacity 2: A and B get through, C waits
        assertThat(queueService.isPassTokenValid(eventId, tokenA)).isTrue();
        assertThat(queueService.isPassTokenValid(eventId, tokenB)).isTrue();
        assertThat(queueService.isPassTokenValid(eventId, tokenC)).isFalse();

        int released = queueService.releaseSlotsOfBuyer(a, eventId); // A paid

        assertThat(released).isEqualTo(1);
        assertThat(queueService.isPassTokenValid(eventId, tokenA)).isFalse(); // A's pass is spent
        assertThat(queueService.isPassTokenValid(eventId, tokenB)).isTrue(); // B is untouched
        sweeper.admitForEvent(eventId);
        assertThat(queueService.isPassTokenValid(eventId, tokenC)).isTrue(); // C took the freed slot
    }

    @Test
    void someoneWhoHoldsNoActiveSlotChangesNothing() {
        long a = user();
        long waitingUser = user();
        String tokenA = enter(a);
        enter(user());
        enter(waitingUser); // capacity 2: this one is still waiting
        sweeper.admitForEvent(eventId);

        assertThat(queueService.releaseSlotsOfBuyer(waitingUser, eventId)).isZero();
        assertThat(queueService.releaseSlotsOfBuyer(user(), eventId)).isZero(); // never queued at all
        assertThat(queueService.isPassTokenValid(eventId, tokenA)).isTrue();
    }

    @Test
    void handingBackTwiceIsHarmless() {
        long a = user();
        long b = user();
        enter(a);
        String tokenB = enter(b);
        sweeper.admitForEvent(eventId);

        assertThat(queueService.releaseSlotsOfBuyer(a, eventId)).isEqualTo(1);
        assertThat(queueService.releaseSlotsOfBuyer(a, eventId)).isZero(); // the same message delivered again
        assertThat(queueService.isPassTokenValid(eventId, tokenB)).isTrue();
    }

    @Test
    void onlyTheSlotForTheEventThatWasPaidForIsHandedBack() {
        long a = user();
        long otherEvent = eventId + 1;
        String tokenHere = enter(a);
        QueueEnterResponse other = queueService.enter(a, otherEvent);
        tokens.add(other.queueToken());
        sweeper.admitForEvent(eventId);
        sweeper.admitForEvent(otherEvent);

        queueService.releaseSlotsOfBuyer(a, eventId);

        assertThat(queueService.isPassTokenValid(eventId, tokenHere)).isFalse();
        assertThat(queueService.isPassTokenValid(otherEvent, other.queueToken())).isTrue();
        redissonClient.getKeys().delete("queue:waiting:" + otherEvent, "queue:active:" + otherEvent, "queue:seq:" + otherEvent);
    }
}
