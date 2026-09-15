package com.ticketing.reservationservice.internal;

import com.ticketing.reservationservice.internal.dto.ReservationDetailResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only - not routed through the public Gateway, reached directly within the docker network. */
@RestController
@RequestMapping("/internal/reservations")
public class ReservationInternalController {

    private final ReservationInternalService reservationInternalService;

    public ReservationInternalController(ReservationInternalService reservationInternalService) {
        this.reservationInternalService = reservationInternalService;
    }

    @GetMapping("/{reservationId}")
    public ReservationDetailResponse getReservation(@PathVariable Long reservationId) {
        return reservationInternalService.getReservation(reservationId);
    }

    @PostMapping("/{reservationId}/confirm")
    public ReservationDetailResponse confirm(@PathVariable Long reservationId) {
        return reservationInternalService.confirm(reservationId);
    }

    @PostMapping("/{reservationId}/cancel")
    public ReservationDetailResponse cancel(@PathVariable Long reservationId) {
        return reservationInternalService.cancel(reservationId);
    }
}
