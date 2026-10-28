package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a waiting client is told over its stream: where it stands when it connects, the pass the moment it is let in, a
 * smaller rank when people ahead of it go in, and the right answer even when the announcement was lost. The sink is a
 * recording fake, so no HTTP is involved. Against the compose Redis and MySQL; run inside the compose network with
 * DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {
    "queue.active-capacity=2",
    "queue.admission-interval-ms=3600000",
    "queue.stream.flush-interval-ms=3600000",
    "queue.stream.reconcile-interval-ms=3600000",
    "queue.stream.heartbeat-interval-ms=3600000",
    "queue.stream.max-connections=4"
})
@Transactional
class QueueStreamsTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueStreams streams;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 5_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        tokens.forEach(streams::remove);
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId);
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t));
    }

    private static final class Recorder implements QueueStreamSink {
        final List<QueueStatusResponse> updates = new CopyOnWriteArrayList<>();
        volatile boolean completed;

        @Override
        public void send(String event, Object data) {
            updates.add((QueueStatusResponse) data);
        }

        @Override
        public void heartbeat() {}

        @Override
        public void complete() {
            completed = true;
        }

        QueueStatusResponse last() {
            return updates.get(updates.size() - 1);
        }
    }

    private String join() {
        String token = queueService.enter(9_100_000_000L + SEQ.incrementAndGet() % 1_000_000, eventId).queueToken();
        tokens.add(token);
        return token;
    }

    private Recorder connect(String token) {
        Recorder recorder = new Recorder();
        streams.register(token, recorder);
        return recorder;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("happened within 5s").isTrue();
    }

    @Test
    void aNewConnectionIsToldWhereItStandsAtOnce() {
        join();
        join();
        String third = join();

        Recorder recorder = connect(third);

        assertThat(recorder.updates).hasSize(1);
        assertThat(recorder.last().status()).isEqualTo("WAITING");
        assertThat(recorder.last().rankNo()).isEqualTo(3);
    }

    @Test
    void whoeverIsLetInIsToldAtOnceAndTheRestMoveUp() throws Exception {
        String a = join();
        String b = join();
        String c = join();
        String d = join();
        Recorder ra = connect(a);
        Recorder rb = connect(b);
        Recorder rc = connect(c);
        Recorder rd = connect(d);

        sweeper.admitForEvent(eventId); // capacity 2: a and b go in

        await(() -> ra.completed && rb.completed);
        assertThat(ra.last().status()).isEqualTo("PASSED");
        assertThat(ra.last().passToken()).isEqualTo(a);
        assertThat(rb.last().status()).isEqualTo("PASSED");
        // c and d are told their new places on the flush (at most once a second), not on every batch
        await(() -> rc.updates.size() == 1 && streams.connections() == 2);
        streams.flushRanks();
        assertThat(rc.last().rankNo()).isEqualTo(1);
        assertThat(rd.last().rankNo()).isEqualTo(2);
        assertThat(rc.completed).isFalse();
    }

    @Test
    void ranksAreOnlySentWhenSomethingMoved() {
        String a = join();
        Recorder ra = connect(a);

        streams.flushRanks();
        streams.flushRanks();

        assertThat(ra.updates).hasSize(1);
    }

    @Test
    void theRefreshStaysQuietWhenNothingChangedAndCorrectsARankThatDrifted() {
        String a = join();
        join();
        String c = join();
        Recorder rc = connect(c);
        assertThat(rc.last().rankNo()).isEqualTo(3);

        streams.reconcile();
        assertThat(rc.updates).hasSize(1); // same rank as before: nothing to say

        // someone ahead leaves the line without being let in (this instance was never told)
        redissonClient.getScoredSortedSet(QueueService.waitingKey(eventId)).remove(a);
        streams.reconcile();

        assertThat(rc.updates).hasSize(2);
        assertThat(rc.last().rankNo()).isEqualTo(2);
    }

    @Test
    void aLostAnnouncementIsMadeUpForByTheRefresh() {
        String a = join();
        String b = join();
        join();
        Recorder ra = connect(a);
        Recorder rb = connect(b);
        // let a and b in without announcing it, as if the pub/sub message never arrived
        RScoredSortedSet<String> active = redissonClient.getScoredSortedSet(QueueService.activeKey(eventId));
        RScoredSortedSet<String> waiting = redissonClient.getScoredSortedSet(QueueService.waitingKey(eventId));
        active.add(System.currentTimeMillis() + 60_000, a);
        active.add(System.currentTimeMillis() + 60_000, b);
        waiting.remove(a);
        waiting.remove(b);
        assertThat(ra.updates).hasSize(1);

        streams.reconcile();

        assertThat(ra.last().status()).isEqualTo("PASSED");
        assertThat(ra.completed).isTrue();
        assertThat(rb.last().status()).isEqualTo("PASSED");
    }

    @Test
    void anUnknownTokenIsRefusedAndNotKept() {
        int before = streams.connections();

        assertThatThrownBy(() -> streams.register("no-such-token", new Recorder()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUEUE_TOKEN_NOT_FOUND));

        assertThat(streams.connections()).isEqualTo(before);
    }

    @Test
    void anAlreadyAdmittedClientGetsItsPassAndNoConnectionIsKept() {
        String a = join();
        sweeper.admitForEvent(eventId);
        int before = streams.connections();

        Recorder recorder = connect(a);

        assertThat(recorder.last().status()).isEqualTo("PASSED");
        assertThat(recorder.completed).isTrue();
        assertThat(streams.connections()).isEqualTo(before);
    }

    @Test
    void whenThisInstanceIsFullTheClientIsTurnedAwayToPolling() {
        List<String> joined = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            joined.add(join());
        }
        int open = 0;
        ApiException refused = null;
        for (String token : joined) {
            try {
                connect(token);
                open++;
            } catch (ApiException e) {
                refused = e;
                break;
            }
        }

        assertThat(refused).isNotNull();
        assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.QUEUE_STREAM_FULL);
        assertThat(open).isLessThanOrEqualTo(4);
    }
}
