package com.ticketing.reservationservice.reservation;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.reservationservice.messaging.ReservationCreatedEvent;
import com.ticketing.reservationservice.messaging.ReservationEventPublisher;
import com.ticketing.reservationservice.queueclient.QueueServiceClient;
import com.ticketing.reservationservice.reservation.dto.ReservationCreateRequest;
import com.ticketing.reservationservice.reservation.dto.ReservationResponse;
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
    private final QueueServiceClient queueServiceClient;
    private final ReservationEventPublisher reservationEventPublisher;

    public ReservationService(
            ReservationRepository reservationRepository,
            EventServiceClient eventServiceClient,
            QueueServiceClient queueServiceClient,
            ReservationEventPublisher reservationEventPublisher) {
        this.reservationRepository = reservationRepository;
        this.eventServiceClient = eventServiceClient;
        this.queueServiceClient = queueServiceClient;
        this.reservationEventPublisher = reservationEventPublisher;
    }

    public ReservationResponse reserve(Long userId, ReservationCreateRequest request, String passToken) {
        SeatDetailResponse requestedSeat = eventServiceClient.getSeat(request.seatId());
        if (!queueServiceClient.isPassTokenValid(requestedSeat.eventId(), passToken)) {
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
