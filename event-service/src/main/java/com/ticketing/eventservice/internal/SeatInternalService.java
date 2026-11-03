package com.ticketing.eventservice.internal;

import com.ticketing.eventservice.alert.SeatsReleasedEvent;
import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.internal.dto.SeatDetailResponse;
import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatLockService;
import com.ticketing.eventservice.seat.SeatRepository;
import com.ticketing.eventservice.seat.SeatStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SeatInternalService {

    private final SeatRepository seatRepository;
    private final SeatLockService seatLockService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public SeatInternalService(
            SeatRepository seatRepository,
            SeatLockService seatLockService,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.seatRepository = seatRepository;
        this.seatLockService = seatLockService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public SeatDetailResponse getSeat(Long seatId) {
        Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
        return SeatDetailResponse.from(seat);
    }

    @Transactional(readOnly = true)
    public List<SeatDetailResponse> getSeats(List<Long> seatIds) {
        return seatRepository.findAllById(seatIds).stream().map(SeatDetailResponse::from).toList();
    }

    /**
     * 좌석별 Redis 잠금을 먼저 잡고, 트랜잭션은 그 안에 둔다. DB 커넥션은 상태 확인과 갱신 동안에만 빌리고(잠금을
     * 기다리는 동안은 빌리지 않는다) 커밋은 잠금을 풀기 전에 일어나므로, 같은 좌석에 대한 다음 요청은 이 홀드가
     * 보이게 된 뒤에야 들어올 수 있다. (예전 구조는 메서드 전체를 @Transactional로 감싸고 그 안에서 잠금을 잡아서,
     * 잠금을 최대 500ms 기다리는 동안 커넥션을 붙잡고 커밋보다 잠금을 조금 먼저 풀었다.) Seat의 @Version 검사는
     * 마지막 방어선으로 남는다.
     */
    public SeatDetailResponse hold(Long seatId, LocalDateTime holdExpireAt) {
        try {
            SeatDetailResponse held = seatLockService.executeWithLock(seatId, () -> transactionTemplate.execute(status -> {
                Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
                if (seat.getStatus() != SeatStatus.AVAILABLE) {
                    throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
                }
                seat.hold(holdExpireAt);
                return SeatDetailResponse.from(seat);
            }));
            meterRegistry.counter("seat_hold_total", "result", "ok").increment();
            return held;
        } catch (ApiException e) {
            // 충돌(이미 잡혔거나 좌석 잠금이 사용 중)은 많은 사람이 원하는 좌석에서 나오는 정상적인 결과다
            String result = e.getErrorCode() == ErrorCode.SEAT_ALREADY_RESERVED ? "conflict" : "error";
            meterRegistry.counter("seat_hold_total", "result", result).increment();
            throw e;
        }
    }

    /**
     * 좌석을 돌려준다. {@code expectedHoldExpireAt}이 있으면 좌석이 바로 그 홀드에 아직 잡혀 있는 동안만 풀고,
     * 아니면 아무것도 하지 않는다. 해제를 여러 주체가 비동기로 요청하기 때문이다(사용자의 취소, 실패한 결제, 서로
     * 독립적인 두 만료 스위퍼). 이미 없어진 홀드에 대한 늦은 요청이 그 사이 좌석을 잡은 사람의 홀드를 풀어서는 안 된다.
     * 이 값이 없으면(null) 예전처럼 조건 없이 푼다.
     */
    public void release(Long seatId, LocalDateTime expectedHoldExpireAt) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
                if (expectedHoldExpireAt != null && !seat.isHeldWith(expectedHoldExpireAt)) {
                    return; // 그 홀드는 이미 없다(만료됐거나 풀렸거나 팔렸다). 우리가 돌려줄 것이 없다
                }
                // reservation-service와 SeatHoldExpirySweeper가 모두 만료된 홀드를 풀기 때문에 두 번째 호출은 이미
                // AVAILABLE인 좌석에 닿는다. 그것을 대기자 입장에서 새로 풀린 좌석으로 세면 안 된다.
                boolean wasUnavailable = seat.getStatus() != SeatStatus.AVAILABLE;
                seat.release();
                if (wasUnavailable) {
                    eventPublisher.publishEvent(new SeatsReleasedEvent(seat.getEvent().getId(), 1));
                }
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            // 읽은 뒤 커밋하기 전에 다른 쪽이 좌석을 바꿨다(예: 다른 스위퍼가 먼저 풀었다).
            // 어느 쪽이든 우리가 풀려던 홀드는 더는 우리가 풀 수 있는 것이 아니다.
        }
    }

    public void sell(Long seatId) {
        sell(seatId, null);
    }

    /**
     * 결제를 위해 좌석을 판매 처리한다. {@code expectedHoldExpireAt}이 있으면 좌석이 바로 그 홀드에 아직 잡혀 있는
     * 동안만 판다. 구매자가 결제하는 동안 홀드가 끝났다면 좌석은 이미 풀렸거나 다른 사람이 잡았을 수 있고, 그래도 팔면
     * 같은 좌석이 두 사람에게 간다. 그 경우 SEAT_HOLD_LOST를 던지고 호출한 쪽이 환불해야 한다. 같은 홀드로 이미 팔린
     * 좌석을 다시 판매 처리하는 것은 아무 일도 하지 않으므로, 일부가 실패한 뒤 다시 시도한 결제도 통과한다.
     * hold()와 같은 좌석별 잠금을 쓴다.
     */
    public void sell(Long seatId, LocalDateTime expectedHoldExpireAt) {
        if (expectedHoldExpireAt == null) {
            transactionTemplate.executeWithoutResult(status -> {
                Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
                seat.sell();
            });
            return;
        }
        seatLockService.executeWithLock(seatId, () -> transactionTemplate.execute(status -> {
            Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
            if (seat.isHeldWith(expectedHoldExpireAt)) {
                seat.sell();
            } else if (!seat.isSoldFrom(expectedHoldExpireAt)) {
                throw new ApiException(ErrorCode.SEAT_HOLD_LOST);
            }
            return null;
        }));
    }
}
