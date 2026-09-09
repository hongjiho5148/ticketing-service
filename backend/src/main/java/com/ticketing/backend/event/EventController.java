package com.ticketing.backend.event;

import com.ticketing.backend.event.dto.EventDetailResponse;
import com.ticketing.backend.event.dto.EventListResponse;
import com.ticketing.backend.seat.dto.SeatResponse;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public EventListResponse listEvents(
            @RequestParam(required = false) EventStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return eventService.listEvents(status, pageable);
    }

    @GetMapping("/{eventId}")
    public EventDetailResponse getEvent(@PathVariable Long eventId) {
        return eventService.getEventDetail(eventId);
    }

    @GetMapping("/{eventId}/seats")
    public List<SeatResponse> listSeats(@PathVariable Long eventId, @RequestParam(required = false) String grade) {
        return eventService.listSeats(eventId, grade);
    }
}
