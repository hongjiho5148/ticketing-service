package com.ticketing.backend.reservation;

import com.ticketing.backend.auth.SecurityUtil;
import com.ticketing.backend.reservation.dto.ReservationCreateRequest;
import com.ticketing.backend.reservation.dto.ReservationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReservationCreateRequest request) {
        ReservationResponse response = reservationService.reserve(SecurityUtil.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{reservationId}")
    public ResponseEntity<Void> cancel(@PathVariable Long reservationId) {
        reservationService.cancel(SecurityUtil.getCurrentUserId(), reservationId);
        return ResponseEntity.noContent().build();
    }
}
