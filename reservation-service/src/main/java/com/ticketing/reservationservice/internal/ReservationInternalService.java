package com.ticketing.reservationservice.internal;

import com.ticketing.reservationservice.common.ApiException;
import com.ticketing.reservationservice.common.ErrorCode;
import com.ticketing.reservationservice.eventclient.EventServiceClient;
import com.ticketing.reservationservice.internal.dto.ReservationDetailResponse;
import com.ticketing.reservationservice.reservation.Reservation;
import com.ticketing.reservationservice.reservation.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationInternalService {

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;

    public ReservationInternalService(ReservationRepository reservationRepository, EventServiceClient eventServiceClient) {
        this.reservationRepository = reservationRepository;
        this.eventServiceClient = eventServiceClient;
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
    public ReservationDetailResponse confirm(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        reservation.confirm();
        eventServiceClient.sell(reservation.getSeatId());
        return ReservationDetailResponse.from(reservation);
    }

    public ReservationDetailResponse cancel(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        reservation.cancel();
        eventServiceClient.release(reservation.getSeatId());
        return ReservationDetailResponse.from(reservation);
    }
}
