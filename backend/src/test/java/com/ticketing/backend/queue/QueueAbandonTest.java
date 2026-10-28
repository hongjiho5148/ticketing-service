package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * People who leave the line without being let in are taken out before their turn, so the slots go to people who are
 * still waiting. "Left" is decided from when each person was last heard from (they hold a stream open, or ask for their
 * status), kept in a Redis sorted set; these tests age those entries by hand instead of waiting out the grace period.
 * Against the compose Redis and MySQL; run inside the compose network with DB_HOST=mysql REDIS_HOST=redis.
 */
@SpringBootTest(properties = {
    "queue.active-capacity=2",
    "queue.admission-interval-ms=3600000",
    "queue.abandon-grace-ms=60000",
    "queue.stream.flush-interval-ms=3600000",
    "queue.stream.reconcile-interval-ms=3600000",
    "queue.stream.heartbeat-interval-ms=3600000",
    "queue.stream.seen-interval-ms=3600000"
})
@Transactional
class QueueAbandonTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());
    private static final double LONG_AGO = 120_000; // twice the grace period

    @Autowired private QueueService queueService;
    @Autowired private QueueStreams streams;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private WaitingQueueRepository waitingQueueRepository;
    @Autowired private RedissonClient redissonClient;

    @MockitoBean private EventServiceClient eventServiceClient;

    private final Long eventId = 4_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        tokens.forEach(streams::remove);
        redissonClient.getKeys().delete("queue:waiting:" + eventId, "queue:active:" + eventId, "queue:seq:" + eventId,
                QueueService.seenKey(eventId));
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t));
    }

    private String join() {
        String token = queueService.enter(9_400_000_000L + SEQ.incrementAndGet() % 1_000_000, eventId).queueToken();
        tokens.add(token);
        return token;
    }

    private RScoredSortedSet<String> seen() {
        return redissonClient.getScoredSortedSet(QueueService.seenKey(eventId));
    }

    private void lastHeardFrom(String token, double msAgo) {
        seen().add(System.currentTimeMillis() - msAgo, token);
    }

    @Test
    void someoneWhoJoinsIsNotedAsBeingHere() {
        String a = join();

        Double score = seen().getScore(a);

        assertThat(score).isNotNull();
        assertThat(System.currentTimeMillis() - score).isLessThan(10_000);
    }

    @Test
    void peopleNotHeardFromAreTakenOutAndTheSlotsGoToThoseWhoAreStillWaiting() {
        String a = join();
        String b = join();
        String c = join();
        String d = join();
        lastHeardFrom(a, LONG_AGO);
        lastHeardFrom(b, LONG_AGO);

        sweeper.admitForEvent(eventId); // capacity 2: without the clean-up a and b would take both slots

        assertThat(queueService.isPassTokenValid(eventId, c)).isTrue();
        assertThat(queueService.isPassTokenValid(eventId, d)).isTrue();
        assertThat(queueService.isPassTokenValid(eventId, a)).isFalse();
        assertThat(queueService.isPassTokenValid(eventId, b)).isFalse();
        assertThat(waitingQueueRepository.findByQueueToken(a).orElseThrow().getStatus()).isEqualTo(QueueEntryStatus.ABANDONED);
        assertThat(waitingQueueRepository.findByQueueToken(c).orElseThrow().getStatus()).isEqualTo(QueueEntryStatus.PASSED);
    }

    @Test
    void whoeverComesBackAfterBeingTakenOutFindsTheirTokenGone() {
        String a = join();
        join();
        lastHeardFrom(a, LONG_AGO);

        sweeper.admitForEvent(eventId);

        assertThatThrownBy(() -> queueService.poll(a))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUEUE_TOKEN_NOT_FOUND));
    }

    @Test
    void askingForTheStatusCountsAsBeingHere() {
        String a = join();
        join();
        join();
        join();
        lastHeardFrom(a, LONG_AGO);

        QueueStatusResponse status = queueService.poll(a); // a is back
        sweeper.admitForEvent(eventId);

        assertThat(status.status()).isEqualTo("WAITING");
        assertThat(queueService.isPassTokenValid(eventId, a)).isTrue(); // still first in line
    }

    @Test
    void holdingAStreamOpenCountsAsBeingHere() {
        String a = join();
        join();
        join();
        join();
        streams.register(a, new QueueStreamSink() {
            @Override
            public void send(String event, Object data) {}

            @Override
            public void heartbeat() {}

            @Override
            public void complete() {}
        });
        lastHeardFrom(a, LONG_AGO);

        streams.refreshSeen(); // what the stream's instance does every few seconds
        sweeper.admitForEvent(eventId);

        assertThat(queueService.isPassTokenValid(eventId, a)).isTrue();
    }

    @Test
    void thoseLetInAreNoLongerTracked() {
        String a = join();
        String b = join();
        String c = join();

        sweeper.admitForEvent(eventId);

        assertThat(seen().getScore(a)).isNull();
        assertThat(seen().getScore(b)).isNull();
        assertThat(seen().getScore(c)).isNotNull(); // still waiting
    }
}
