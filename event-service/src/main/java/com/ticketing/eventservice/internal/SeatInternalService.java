package com.ticketing.eventservice.internal;

import com.ticketing.eventservice.alert.SeatsReleasedEvent;
import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.internal.dto.SeatDetailResponse;
import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatLockService;
import com.ticketing.eventservice.seat.SeatRepository;
import com.ticketing.eventservice.seat.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SeatInternalService {

    private final SeatRepository seatRepository;
    private final SeatLockService seatLockService;
    private final ApplicationEventPublisher eventPublisher;

    public SeatInternalService(
            SeatRepository seatRepository, SeatLockService seatLockService, ApplicationEventPublisher eventPublisher) {
        this.seatRepository = seatRepository;
        this.seatLockService = seatLockService;
        this.eventPublisher = eventPublisher;
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

    // Same critical section ReservationService used to run in-process: per-seat Redis lock, then
    // status check + hold, all before the surrounding @Transactional commits.
    public SeatDetailResponse hold(Long seatId, LocalDateTime holdExpireAt) {
        return seatLockService.executeWithLock(seatId, () -> {
            Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
            }
            seat.hold(holdExpireAt);
            return SeatDetailResponse.from(seat);
        });
    }

    public void release(Long seatId) {
        Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
        // Both reservation-service and SeatHoldExpirySweeper release expired holds, so the second call
        // lands on an already-AVAILABLE seat - that must not count as a freshly freed seat for the waitlist.
        boolean wasUnavailable = seat.getStatus() != SeatStatus.AVAILABLE;
        seat.release();
        if (wasUnavailable) {
            eventPublisher.publishEvent(new SeatsReleasedEvent(seat.getEvent().getId(), 1));
        }
    }

    public void sell(Long seatId) {
        Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
        seat.sell();
    }
}
