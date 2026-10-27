package com.ticketing.reservationservice.internal;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.internal.dto.ReservationDetailResponse;
import com.ticketing.reservationservice.messaging.KafkaTopics;
import com.ticketing.reservationservice.messaging.OutboxRecorder;
import com.ticketing.reservationservice.messaging.ReservationConfirmedEvent;
import com.ticketing.reservationservice.reservation.Reservation;
import com.ticketing.reservationservice.reservation.ReservationRepository;
import com.ticketing.reservationservice.reservation.ReservationStatus;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationInternalService {

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;
    private final OutboxRecorder outboxRecorder;

    public ReservationInternalService(
            ReservationRepository reservationRepository,
            EventServiceClient eventServiceClient,
            OutboxRecorder outboxRecorder) {
        this.reservationRepository = reservationRepository;
        this.eventServiceClient = eventServiceClient;
        this.outboxRecorder = outboxRecorder;
    }

    @Transactional(readOnly = true)
    public ReservationDetailResponse getReservation(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        return ReservationDetailResponse.from(reservation);
    }

    // Order-service delegates the "reservation lifecycle -> seat state" transition here instead of
    // calling event-service directly - this is the same responsibility ReservationExpirySweeper
    // already has when a hold lapses, just triggered by a successful payment instead of a timeout.
    /**
     * Only a reservation whose hold is still alive can be confirmed. An expired or cancelled one means the seat may
     * already be free or held by someone else, so HOLD_EXPIRED goes back to order-service, which refunds the payment.
     * Confirming twice is fine (a payment retried after a partial failure). The seat sale itself is guarded by the
     * hold too, so even a reservation the sweeper has not expired yet cannot sell a seat that was re-held meanwhile.
     */
    public ReservationDetailResponse confirm(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return ReservationDetailResponse.from(reservation);
        }
        if (reservation.getStatus() != ReservationStatus.HOLDING) {
            throw new ApiException(ErrorCode.HOLD_EXPIRED);
        }
        reservation.confirm();
        eventServiceClient.sell(reservation.getSeatId(), reservation.getHoldExpireAt());
        // Recorded in this transaction, so the announcement exists exactly when the confirmation commits. A repeated
        // confirm returns above and records nothing, so there is one event per reservation.
        outboxRecorder.record(
                KafkaTopics.RESERVATION_CONFIRMED,
                reservation.getId().toString(),
                new ReservationConfirmedEvent(
                        reservation.getId(),
                        reservation.getSeatId(),
                        reservation.getUserId(),
                        reservation.getEventId(),
                        LocalDateTime.now().toString()));
        return ReservationDetailResponse.from(reservation);
    }

    public ReservationDetailResponse cancel(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        reservation.cancel();
        eventServiceClient.release(reservation.getSeatId(), reservation.getHoldExpireAt());
        return ReservationDetailResponse.from(reservation);
    }
}
