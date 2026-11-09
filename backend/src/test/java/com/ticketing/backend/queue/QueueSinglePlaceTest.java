package com.ticketing.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import io.micrometer.core.instrument.MeterRegistry;
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
 * 한 사람은 공연당 줄에 자리를 하나만 가진다. 이미 줄에 있는 사람이 다시 들어오면(새로고침, 탭 여러 개, 개발 모드의 이중 실행)
 * 새 대기표를 만들지 않고 기존 것을 돌려준다. compose Redis와 MySQL에서 실행한다(데이터베이스 부분은 롤백하고, 테스트 공연의
 * Redis 키는 끝나고 지운다). 용량은 2라서 세 번째 사람은 기다려야 하고, 입장 스위퍼의 자체 타이머는 꺼 두어서 테스트가 사람들을
 * 입장시킬 때를 정한다. compose 네트워크 안에서 DB_HOST=mysql REDIS_HOST=redis로 실행한다.
 */
@SpringBootTest(properties = {"queue.active-capacity=2", "queue.admission-interval-ms=3600000"})
@Transactional
class QueueSinglePlaceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private QueueService queueService;
    @Autowired private QueueAdmissionSweeper sweeper;
    @Autowired private RedissonClient redissonClient;
    @Autowired private WaitingQueueRepository waitingQueueRepository;
    @Autowired private MeterRegistry meterRegistry;

    @MockitoBean private EventServiceClient eventServiceClient;

    private Long eventId;
    private final List<Long> users = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();

    @BeforeEach
    void setUp() {
        eventId = 7_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
    }

    @AfterEach
    void cleanUpRedis() {
        for (long id : new long[] {eventId, eventId + 1}) {
            redissonClient.getKeys().delete("queue:waiting:" + id, "queue:active:" + id, "queue:seq:" + id, "queue:seen:" + id);
            users.forEach(u -> redissonClient.getKeys().delete("queue:place:" + id + ":" + u));
        }
        tokens.forEach(t -> redissonClient.getKeys().delete("queue:event:" + t));
    }

    private long user() {
        long id = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        users.add(id);
        return id;
    }

    private QueueEnterResponse enter(long userId) {
        QueueEnterResponse response = queueService.enter(userId, eventId);
        tokens.add(response.queueToken());
        return response;
    }

    private int waitingCount() {
        return redissonClient.getScoredSortedSet("queue:waiting:" + eventId).size();
    }

    private double reentered() {
        return meterRegistry.counter("queue_reentered_total").count();
    }

    @Test
    void enteringAgainReturnsTheSameWaitingPlaceInsteadOfANewOne() {
        long a = user();
        enter(user());
        enter(user());
        sweeper.admitForEvent(eventId); // 용량 2: 앞의 두 사람이 입장하므로 a는 기다린다
        QueueEnterResponse first = enter(a);
        double before = reentered();

        QueueEnterResponse second = enter(a);

        assertThat(second.queueToken()).isEqualTo(first.queueToken());
        assertThat(second.rankNo()).isEqualTo(first.rankNo()).isEqualTo(1);
        assertThat(waitingCount()).isEqualTo(1); // 줄에는 이 사람 하나뿐이다
        assertThat(waitingQueueRepository.findByUserIdAndEventId(a, eventId)).hasSize(1); // 이력 행도 하나다
        assertThat(reentered() - before).isEqualTo(1.0);
    }

    @Test
    void anAdmittedPersonEnteringAgainGetsTheSamePassBack() {
        long a = user();
        QueueEnterResponse first = enter(a);
        enter(user());
        enter(user());
        sweeper.admitForEvent(eventId); // 용량 2: a와 두 번째 사람이 입장하고 셋째는 기다린다

        QueueEnterResponse again = enter(a);

        assertThat(again.queueToken()).isEqualTo(first.queueToken());
        assertThat(again.rankNo()).isZero(); // 순번 없이 돌려준다. 상태를 물으면 PASSED가 온다
        assertThat(queueService.isPassTokenValid(eventId, again.queueToken())).isTrue();
        assertThat(queueService.status(again.queueToken()).status()).isEqualTo("PASSED");
        assertThat(waitingCount()).isEqualTo(1);
    }

    @Test
    void aPlaceThatIsGoneIsReplacedByANewOne() {
        long a = user();
        QueueEnterResponse first = enter(a);
        sweeper.admitForEvent(eventId);
        assertThat(queueService.releaseSlotsOfBuyer(a, eventId)).isEqualTo(1); // a가 결제해서 슬롯을 돌려줬다

        QueueEnterResponse again = enter(a);

        assertThat(again.queueToken()).isNotEqualTo(first.queueToken()); // 결제한 뒤 좌석을 더 사려면 다시 줄을 선다
        assertThat(again.rankNo()).isEqualTo(1);
    }

    @Test
    void aPersonWhoWasTakenOutOfTheLineCanJoinAgain() {
        long a = user();
        QueueEnterResponse first = enter(a);
        redissonClient.getScoredSortedSet("queue:waiting:" + eventId).remove(first.queueToken()); // 이탈 정리가 빼 버렸다

        QueueEnterResponse again = enter(a);

        assertThat(again.queueToken()).isNotEqualTo(first.queueToken());
        assertThat(waitingCount()).isEqualTo(1);
    }

    @Test
    void theSamePersonOnTwoShowsHasOnePlaceOnEach() {
        long a = user();
        QueueEnterResponse here = enter(a);
        QueueEnterResponse there = queueService.enter(a, eventId + 1);
        tokens.add(there.queueToken());

        assertThat(there.queueToken()).isNotEqualTo(here.queueToken());
        assertThat(queueService.enter(a, eventId + 1).queueToken()).isEqualTo(there.queueToken());
        assertThat(enter(a).queueToken()).isEqualTo(here.queueToken());
    }

    @Test
    void differentPeopleStillGetTheirOwnPlaces() {
        QueueEnterResponse first = enter(user());
        QueueEnterResponse second = enter(user());

        assertThat(second.queueToken()).isNotEqualTo(first.queueToken());
        assertThat(waitingCount()).isEqualTo(2);
    }

    @Test
    void theEstimateCountsThePeopleAheadOnceTheSlotsAreFull() {
        enter(user());
        enter(user());
        QueueEnterResponse third = enter(user());
        sweeper.admitForEvent(eventId); // 용량 2: 처음 둘이 입장하고 셋째는 기다린다

        QueueStatusResponse status = queueService.status(third.queueToken());

        assertThat(status.status()).isEqualTo("WAITING");
        assertThat(status.rankNo()).isEqualTo(1);
        assertThat(status.estimatedWaitSeconds()).isEqualTo(300); // 빈 슬롯 없음 + 한 바퀴 = 입장권 수명 5분
    }

    @Test
    void whenSlotsAreFreeTheEstimateIsTheNextTick() {
        QueueEnterResponse only = enter(user()); // 아직 아무도 입장시키지 않았지만 슬롯 2개가 비어 있다

        assertThat(only.rankNo()).isEqualTo(1);
        assertThat(only.estimatedWaitSeconds()).isEqualTo(3600); // 이 테스트는 입장 주기를 1시간으로 꺼 두었다
    }
}
