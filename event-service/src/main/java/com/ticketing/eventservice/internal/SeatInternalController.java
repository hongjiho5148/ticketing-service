package com.ticketing.eventservice.internal;

import com.ticketing.eventservice.internal.dto.HoldSeatRequest;
import com.ticketing.eventservice.internal.dto.SeatBatchRequest;
import com.ticketing.eventservice.internal.dto.SeatBatchResponse;
import com.ticketing.eventservice.internal.dto.SeatDetailResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only - not routed through the public Gateway, reached directly within the docker network. */
@RestController
@RequestMapping("/internal/seats")
public class SeatInternalController {

    private final SeatInternalService seatInternalService;

    public SeatInternalController(SeatInternalService seatInternalService) {
        this.seatInternalService = seatInternalService;
    }

    @GetMapping("/{seatId}")
    public SeatDetailResponse getSeat(@PathVariable Long seatId) {
        return seatInternalService.getSeat(seatId);
    }

    @PostMapping("/batch")
    public SeatBatchResponse getSeats(@Valid @RequestBody SeatBatchRequest request) {
        return new SeatBatchResponse(seatInternalService.getSeats(request.seatIds()));
    }

    @PostMapping("/{seatId}/hold")
    public SeatDetailResponse hold(@PathVariable Long seatId, @Valid @RequestBody HoldSeatRequest request) {
        return seatInternalService.hold(seatId, request.holdExpireAt());
    }

    @PostMapping("/{seatId}/release")
    public ResponseEntity<Void> release(@PathVariable Long seatId) {
        seatInternalService.release(seatId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{seatId}/sell")
    public ResponseEntity<Void> sell(@PathVariable Long seatId) {
        seatInternalService.sell(seatId);
        return ResponseEntity.ok().build();
    }
}
