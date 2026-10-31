package com.ticketing.reservationservice.reservation;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.reservationservice.messaging.ReservationCreatedEvent;
import com.ticketing.reservationservice.messaging.KafkaTopics;
import com.ticketing.reservationservice.messaging.OutboxRecorder;
import com.ticketing.reservationservice.queueclient.QueueServiceClient;
import com.ticketing.reservationservice.reservation.dto.ReservationCreateRequest;
import com.ticketing.reservationservice.reservation.dto.ReservationResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deliberately NOT @Transactional as a whole: reserve() and cancel() talk to event-service and the queue over
 * HTTP, and a transaction around those calls keeps a DB connection checked out while the thread just waits on
 * the network. With a small pool (Hikari's default is 10) that capped concurrent reservations at roughly the pool
 * size and made everyone else queue for a connection. Each repository call below runs in its own short
 * transaction instead, so a connection is only borrowed while it is actually being used.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;
    private final QueueServiceClient queueServiceClient;
    private final OutboxRecorder outboxRecorder;
    private final TransactionTemplate transactionTemplate;
    private final int maxSeatsPerEvent;

    public ReservationService(
            ReservationRepository reservationRepository,
            EventServiceClient eventServiceClient,
            QueueServiceClient queueServiceClient,
            OutboxRecorder outboxRecorder,
            PlatformTransactionManager transactionManager,
            @Value("${reservation.max-seats-per-event}") int maxSeatsPerEvent) {
        this.reservationRepository = reservationRepository;
        this.eventServiceClient = eventServiceClient;
        this.queueServiceClient = queueServiceClient;
        this.outboxRecorder = outboxRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxSeatsPerEvent = maxSeatsPerEvent;
    }

    public ReservationResponse reserve(Long userId, ReservationCreateRequest request, String passToken) {
        SeatDetailResponse requestedSeat = eventServiceClient.getSeat(request.seatId());
        if (requestedSeat.salesClosed()) {
            // event-service judges this from its own clock: seats can be taken until 2 hours before the show starts
            throw new ApiException(ErrorCode.SALES_CLOSED);
        }
        if (!queueServiceClient.isPassTokenValid(requestedSeat.eventId(), passToken)) {
            throw new ApiException(ErrorCode.PASS_TOKEN_REQUIRED);
        }
        if (reservationRepository.countActiveSeats(userId, requestedSeat.eventId(), LocalDateTime.now())
                >= maxSeatsPerEvent) {
            throw new ApiException(ErrorCode.SEAT_LIMIT_EXCEEDED);
        }

        // event-service holds the same per-seat Redis lock this used to wrap locally, then does
        // the status-check + hold atomically on its side - see EventServiceClient.hold().
        LocalDateTime holdExpireAt = LocalDateTime.now().plus(HOLD_DURATION);
        SeatDetailResponse heldSeat = eventServiceClient.hold(request.seatId(), holdExpireAt);

        Reservation reservation = new Reservation(userId, request.seatId(), heldSeat.eventId(), holdExpireAt);
        // The reservation and the event announcing it are saved in one short transaction: either both exist or neither,
        // so the event can't be lost (or announce a reservation that was never saved). OutboxRelay sends it to Kafka
        // afterwards - the broker is not part of this request.
        Reservation saved;
        try {
            saved = transactionTemplate.execute(status -> {
                Reservation created = reservationRepository.save(reservation);
                outboxRecorder.record(
                        KafkaTopics.RESERVATION_CREATED,
                        created.getId().toString(),
                        new ReservationCreatedEvent(
                                created.getId(),
                                heldSeat.seatId(),
                                userId,
                                heldSeat.eventId(),
                                created.getCreatedAt().toString()));
                return created;
            });
        } catch (RuntimeException e) {
            // The seat is already held in event-service; without a reservation row nobody can cancel or pay for it,
            // so give it back now instead of leaving it stuck until the hold lapses.
            releaseQuietly(request.seatId(), holdExpireAt);
            throw e;
        }

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
        // Release first, then record the cancellation. If the release fails nothing has changed and the user can
        // retry; if saving fails afterwards the retry finds the seat already free (release is idempotent).
        eventServiceClient.release(reservation.getSeatId(), reservation.getHoldExpireAt());
        reservation.cancel();
        reservationRepository.save(reservation);
    }

    private void releaseQuietly(Long seatId, LocalDateTime holdExpireAt) {
        try {
            eventServiceClient.release(seatId, holdExpireAt);
        } catch (RuntimeException e) {
            // The hold-expiry sweepers free it within a few minutes anyway.
            log.warn("Could not release seat {} after a failed reservation save: {}", seatId, e.getMessage());
        }
    }
}
