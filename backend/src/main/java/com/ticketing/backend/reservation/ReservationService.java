package com.ticketing.backend.reservation;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.messaging.ReservationCreatedEvent;
import com.ticketing.backend.messaging.ReservationEventPublisher;
import com.ticketing.backend.queue.QueueService;
import com.ticketing.backend.reservation.dto.ReservationCreateRequest;
import com.ticketing.backend.reservation.dto.ReservationResponse;
import com.ticketing.backend.seat.Seat;
import com.ticketing.backend.seat.SeatLockService;
import com.ticketing.backend.seat.SeatRepository;
import com.ticketing.backend.seat.SeatStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationService {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final SeatLockService seatLockService;
    private final QueueService queueService;
    private final ReservationEventPublisher reservationEventPublisher;

    public ReservationService(
            ReservationRepository reservationRepository,
            SeatRepository seatRepository,
            SeatLockService seatLockService,
            QueueService queueService,
            ReservationEventPublisher reservationEventPublisher) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.seatLockService = seatLockService;
        this.queueService = queueService;
        this.reservationEventPublisher = reservationEventPublisher;
    }

    public ReservationResponse reserve(Long userId, ReservationCreateRequest request, String passToken) {
        Seat requestedSeat = seatRepository
                .findById(request.seatId())
                .orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
        if (!queueService.isPassTokenValid(requestedSeat.getEvent().getId(), passToken)) {
            throw new ApiException(ErrorCode.PASS_TOKEN_REQUIRED);
        }

        // A per-seat Redis lock rejects concurrent contenders immediately, before they ever touch
        // the database - only the request currently holding the lock does DB work. The @Version
        // optimistic lock on Seat is still the real correctness backstop underneath this.
        return seatLockService.executeWithLock(request.seatId(), () -> {
            Seat seat = seatRepository
                    .findById(request.seatId())
                    .orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));

            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new ApiException(ErrorCode.SEAT_ALREADY_RESERVED);
            }
            seat.hold();

            Reservation reservation = new Reservation(userId, seat, LocalDateTime.now().plus(HOLD_DURATION));
            Reservation saved = reservationRepository.save(reservation);

            reservationEventPublisher.publishReservationCreated(new ReservationCreatedEvent(
                    saved.getId(), seat.getId(), userId, seat.getEvent().getId(), saved.getCreatedAt().toString()));

            return ReservationResponse.from(saved);
        });
    }

    public void cancel(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (reservation.getStatus() != ReservationStatus.HOLDING) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
        reservation.cancel();
        reservation.getSeat().release();
    }
}
