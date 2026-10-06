package com.ticketing.eventservice.admin;

import com.ticketing.eventservice.admin.dto.EventStatsResponse;
import com.ticketing.eventservice.admin.dto.EventUpsertRequest;
import com.ticketing.eventservice.admin.dto.SeatBulkCreateRequest;
import com.ticketing.eventservice.admin.dto.SeatBulkCreateResponse;
import com.ticketing.eventservice.admin.dto.SeatPriceUpdateRequest;
import com.ticketing.eventservice.event.dto.EventSummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gated to ROLE_ADMIN by the /api/events/admin/** matcher in SecurityConfig, not by anything in here. */
@RestController
@RequestMapping("/api/events/admin")
public class AdminEventController {

    private final AdminEventService adminEventService;

    public AdminEventController(AdminEventService adminEventService) {
        this.adminEventService = adminEventService;
    }

    @PostMapping
    public ResponseEntity<EventSummaryResponse> createEvent(@Valid @RequestBody EventUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminEventService.createEvent(request));
    }

    @PutMapping("/{eventId}")
    public EventSummaryResponse updateEvent(@PathVariable Long eventId, @Valid @RequestBody EventUpsertRequest request) {
        return adminEventService.updateEvent(eventId, request);
    }

    @PostMapping("/{eventId}/seats")
    public ResponseEntity<SeatBulkCreateResponse> createSeats(
            @PathVariable Long eventId, @Valid @RequestBody SeatBulkCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminEventService.createSeats(eventId, request));
    }

    @PutMapping("/seats/{seatId}")
    public ResponseEntity<Void> updateSeatPrice(
            @PathVariable Long seatId, @Valid @RequestBody SeatPriceUpdateRequest request) {
        adminEventService.updateSeatPrice(seatId, request.price());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public List<EventStatsResponse> stats() {
        return adminEventService.stats();
    }
}
