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
 * 기다리는 클라이언트가 스트림으로 듣는 것이다. 연결했을 때 어디에 서 있는지, 입장하는 순간 입장권, 앞사람들이 들어가면 더 작은
 * 순번, 그리고 알림이 유실됐을 때도 맞는 답. 싱크는 기록하는 가짜라서 HTTP는 끼어들지 않는다. compose Redis와 MySQL에서 실행하고,
 * compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
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

        sweeper.admitForEvent(eventId); // 용량 2: a와 b가 입장한다

        await(() -> ra.completed && rb.completed);
        assertThat(ra.last().status()).isEqualTo("PASSED");
        assertThat(ra.last().passToken()).isEqualTo(a);
        assertThat(ra.last().passExpiresInSeconds()).isBetween(295L, 300L); // 기본 5분 입장권이다. 브라우저에 알려 준 그대로
        assertThat(rb.last().status()).isEqualTo("PASSED");
        // c와 d는 묶음마다가 아니라 반영 시점(초당 한 번 이하)에 새 자리를 알린다
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
        assertThat(rc.updates).hasSize(1); // 이전과 같은 순번: 알릴 것이 없다

        // 앞에 있던 사람이 입장하지 않고 줄을 떠난다(이 인스턴스는 전달받지 못했다)
        redissonClient.getScoredSortedSet(QueueService.waitingKey(eventId)).remove(a);
        streams.reconcile();

        assertThat(rc.updates).hasSize(2);
        assertThat(rc.last().rankNo()).isEqualTo(2);
    }

    @Test
    void readingManyAtOnceAgreesWithReadingOneAtATime() {
        List<String> line = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            line.add(join());
        }
        sweeper.admitForEvent(eventId); // 용량 2: 처음 둘이 입장하고 셋은 기다린다
        redissonClient.getScoredSortedSet(QueueService.waitingKey(eventId)).remove(line.get(3)); // 한 명이 줄을 떠난다
        java.util.Map<String, Long> asked = new java.util.HashMap<>();
        line.forEach(t -> asked.put(t, eventId));

        java.util.Map<String, QueueStatusResponse> many = queueService.statusOfMany(asked);

        assertThat(many).containsOnlyKeys(line.get(0), line.get(1), line.get(2), line.get(4)); // 떠난 사람은 빠져 있다
        for (String token : many.keySet()) {
            QueueStatusResponse one = queueService.status(token);
            assertThat(many.get(token).status()).isEqualTo(one.status());
            assertThat(many.get(token).rankNo()).isEqualTo(one.rankNo());
            assertThat(many.get(token).passToken()).isEqualTo(one.passToken());
            if (one.passExpiresInSeconds() != null) { // 조금 간격을 두고 읽으므로 1초의 여유를 둔다
                assertThat(many.get(token).passExpiresInSeconds()).isBetween(one.passExpiresInSeconds() - 1, one.passExpiresInSeconds() + 1);
            }
        }
        assertThat(many.get(line.get(2)).rankNo()).isEqualTo(1);
        assertThat(many.get(line.get(4)).rankNo()).isEqualTo(2);
    }

    @Test
    void aLostAnnouncementIsMadeUpForByTheRefresh() {
        String a = join();
        String b = join();
        join();
        Recorder ra = connect(a);
        Recorder rb = connect(b);
        // pub/sub 메시지가 도착하지 않은 것처럼, 알리지 않고 a와 b를 입장시킨다
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
        assertThat(recorder.last().passExpiresInSeconds()).isBetween(295L, 300L);
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
