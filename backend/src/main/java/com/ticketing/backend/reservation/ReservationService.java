package com.ticketing.backend.reservation;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.eventclient.dto.SeatDetailResponse;
import com.ticketing.backend.messaging.ReservationCreatedEvent;
import com.ticketing.backend.messaging.ReservationEventPublisher;
import com.ticketing.backend.queue.QueueService;
import com.ticketing.backend.reservation.dto.ReservationCreateRequest;
import com.ticketing.backend.reservation.dto.ReservationResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationService {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;
    private final QueueService queueService;
    private final ReservationEventPublisher reservationEventPublisher;

    public ReservationService(
            ReservationRepository reservationRepository,
            EventServiceClient eventServiceClient,
            QueueService queueService,
            ReservationEventPublisher reservationEventPublisher) {
        this.reservationRepository = reservationRepository;
        this.eventServiceClient = eventServiceClient;
        this.queueService = queueService;
        this.reservationEventPublisher = reservationEventPublisher;
    }

    public ReservationResponse reserve(Long userId, ReservationCreateRequest request, String passToken) {
        SeatDetailResponse requestedSeat = eventServiceClient.getSeat(request.seatId());
        if (!queueService.isPassTokenValid(requestedSeat.eventId(), passToken)) {
            throw new ApiException(ErrorCode.PASS_TOKEN_REQUIRED);
        }

        // event-service holds the same per-seat Redis lock this used to wrap locally, then does
        // the status-check + hold atomically on its side - see EventServiceClient.hold().
        LocalDateTime holdExpireAt = LocalDateTime.now().plus(HOLD_DURATION);
        SeatDetailResponse heldSeat = eventServiceClient.hold(request.seatId(), holdExpireAt);

        Reservation reservation = new Reservation(userId, request.seatId(), holdExpireAt);
        Reservation saved = reservationRepository.save(reservation);

        reservationEventPublisher.publishReservationCreated(new ReservationCreatedEvent(
                saved.getId(), heldSeat.seatId(), userId, heldSeat.eventId(), saved.getCreatedAt().toString()));

        return ReservationResponse.from(saved);
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
        eventServiceClient.release(reservation.getSeatId());
    }
}
