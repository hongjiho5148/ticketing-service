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
 * 구매자가 결제를 마쳤을 때 그 슬롯을 돌려주는 것이다. compose Redis와 MySQL에서 실행한다(데이터베이스 부분은 롤백하고,
 * 테스트 공연의 Redis 키는 끝나고 지운다). 용량은 2라서 세 번째 사람은 기다려야 하고, 입장 스위퍼의 자체 타이머는 꺼 두어서
 * 테스트가 사람들을 입장시킬 때를 정한다. compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
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
        sweeper.admitForEvent(eventId); // 용량 2: A와 B는 통과하고 C는 기다린다
        assertThat(queueService.isPassTokenValid(eventId, tokenA)).isTrue();
        assertThat(queueService.isPassTokenValid(eventId, tokenB)).isTrue();
        assertThat(queueService.isPassTokenValid(eventId, tokenC)).isFalse();

        int released = queueService.releaseSlotsOfBuyer(a, eventId); // A가 결제했다

        assertThat(released).isEqualTo(1);
        assertThat(queueService.isPassTokenValid(eventId, tokenA)).isFalse(); // A의 입장권은 소진됐다
        assertThat(queueService.isPassTokenValid(eventId, tokenB)).isTrue(); // B는 건드리지 않았다
        sweeper.admitForEvent(eventId);
        assertThat(queueService.isPassTokenValid(eventId, tokenC)).isTrue(); // C가 풀린 슬롯을 가져갔다
    }

    @Test
    void someoneWhoHoldsNoActiveSlotChangesNothing() {
        long a = user();
        long waitingUser = user();
        String tokenA = enter(a);
        enter(user());
        enter(waitingUser); // 용량 2: 이 사람은 아직 기다리는 중
        sweeper.admitForEvent(eventId);

        assertThat(queueService.releaseSlotsOfBuyer(waitingUser, eventId)).isZero();
        assertThat(queueService.releaseSlotsOfBuyer(user(), eventId)).isZero(); // 대기열에 아예 선 적 없음
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
        assertThat(queueService.releaseSlotsOfBuyer(a, eventId)).isZero(); // 같은 메시지가 다시 전달됐다
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
