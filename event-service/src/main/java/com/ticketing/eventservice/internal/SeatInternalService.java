package com.ticketing.eventservice.internal;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.internal.dto.SeatDetailResponse;
import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatLockService;
import com.ticketing.eventservice.seat.SeatRepository;
import com.ticketing.eventservice.seat.SeatStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SeatInternalService {

    private final SeatRepository seatRepository;
    private final SeatLockService seatLockService;

    public SeatInternalService(SeatRepository seatRepository, SeatLockService seatLockService) {
        this.seatRepository = seatRepository;
        this.seatLockService = seatLockService;
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
        seat.release();
    }

    public void sell(Long seatId) {
        Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
        seat.sell();
    }
}
