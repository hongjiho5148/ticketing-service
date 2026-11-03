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
 * 입장하지 못하고 줄을 떠난 사람은 차례가 오기 전에 빠지고, 그 슬롯은 아직 기다리는 사람에게 간다. "떠났다"는
 * 각 사람이 마지막으로 소식을 보낸 시각(스트림을 열어 두었거나 상태를 물었다)으로 판정하며, Redis 정렬 집합에
 * 기록한다. 이 테스트들은 유예 시간을 기다리는 대신 그 항목들을 손으로 오래된 것으로 만든다.
 * compose Redis와 MySQL에서 실행하고, compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
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
    private static final double LONG_AGO = 120_000; // 유예 시간의 두 배

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

        sweeper.admitForEvent(eventId); // 용량 2: 정리가 없었다면 a와 b가 두 슬롯을 모두 차지했을 것이다

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

        QueueStatusResponse status = queueService.poll(a); // a가 돌아왔다
        sweeper.admitForEvent(eventId);

        assertThat(status.status()).isEqualTo("WAITING");
        assertThat(queueService.isPassTokenValid(eventId, a)).isTrue(); // 여전히 줄 맨 앞
    }

    @Test
    void someoneOnlyAskingForTheirStatusIsCreditedForLongerThanAnOpenConnection() {
        String a = join();
        String b = join();

        queueService.poll(a); // 스트림 없음: 백그라운드 탭은 한동안 다시 묻지 않을 수 있다
        streams.register(b, new QueueStreamSink() {
            @Override
            public void send(String event, Object data) {}

            @Override
            public void heartbeat() {}

            @Override
            public void complete() {}
        });
        streams.refreshSeen(); // 열린 연결은 몇 초마다 갱신되므로 얹어 줄 필요가 없다

        double asking = (seen().getScore(a) - System.currentTimeMillis()) / 1000.0;
        double connected = (seen().getScore(b) - System.currentTimeMillis()) / 1000.0;
        assertThat(asking).isBetween(115.0, 121.0);
        assertThat(connected).isBetween(-5.0, 1.0);
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

        streams.refreshSeen(); // 스트림을 쥔 인스턴스가 몇 초마다 하는 일
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
        assertThat(seen().getScore(c)).isNotNull(); // 아직 기다리는 중
    }
}
