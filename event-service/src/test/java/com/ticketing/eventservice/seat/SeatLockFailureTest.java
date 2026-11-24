package com.ticketing.eventservice.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.RedisTimeoutException;

/**
 * Redis가 죽었을 때 좌석 락이 어떻게 동작하는지 검사한다. Redis 없이 도는 단위 테스트다(Redisson을 가짜로 대체).
 *
 * <p>장애 시험에서 실제로 일어난 일이 바탕이다. Redis가 죽은 동안 좌석 락을 푸는 단계에서 예외가 나서, DB에는 홀드가
 * 커밋됐는데 요청은 500으로 끝났고 좌석 3개가 예약 없이 HOLD로 남았다.
 */
class SeatLockFailureTest {

    private RedissonClient redisson;
    private RLock lock;
    private SeatLockService service;

    @BeforeEach
    void setUp() throws Exception {
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock("lock:seat:7")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        service = new SeatLockService(redisson, 500, 3000);
    }

    @Test
    void normalPathRunsTheActionAndReleasesTheLock() {
        String result = service.executeWithLock(7L, () -> "held");

        assertThat(result).isEqualTo("held");
        verify(lock).unlock();
    }

    @Test
    void ifReleasingTheLockFailsBecauseRedisIsDownTheCommittedResultIsStillReturned() {
        doThrow(new RedisTimeoutException("Unable to acquire connection")).when(lock).unlock();

        // 액션(DB 홀드)은 이미 끝났다. 락을 못 푼 것이 요청 실패로 번지면 홀드는 됐는데 예약은 실패하는 상태가 된다.
        String result = service.executeWithLock(7L, () -> "held");

        assertThat(result).isEqualTo("held");
    }

    @Test
    void ifEvenCheckingWhoHoldsTheLockFailsTheResultIsStillReturned() {
        when(lock.isHeldByCurrentThread()).thenThrow(new RedisTimeoutException("Unable to acquire connection"));

        assertThat(service.executeWithLock(7L, () -> "held")).isEqualTo("held");
        verify(lock, never()).unlock();
    }

    @Test
    void anExceptionFromTheActionIsNotHiddenByAFailureToReleaseTheLock() {
        doThrow(new RedisTimeoutException("Unable to acquire connection")).when(lock).unlock();

        assertThatThrownBy(() -> service.executeWithLock(7L, () -> {
            throw new IllegalStateException("db failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("db failed");
    }

    @Test
    void ifTheLockCannotBeAcquiredBecauseRedisIsDownTheRedisErrorPropagatesAndNothingRuns() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RedisTimeoutException("Unable to acquire connection"));
        boolean[] ran = {false};

        // 락을 못 잡았으니 좌석은 건드리지 않는다. 이 오류는 GlobalExceptionHandler가 503으로 바꾼다.
        assertThatThrownBy(() -> service.executeWithLock(7L, () -> {
            ran[0] = true;
            return "held";
        })).isInstanceOf(RedisException.class);
        assertThat(ran[0]).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    void aLockHeldByAnotherRequestStillMeansSeatAlreadyReserved() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

        assertThatThrownBy(() -> service.executeWithLock(7L, () -> "held"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_RESERVED));
    }
}
