package com.ticketing.eventservice.event;

import com.ticketing.eventservice.event.dto.EventDetailResponse;
import com.ticketing.eventservice.event.dto.EventListResponse;
import com.ticketing.eventservice.seat.dto.SeatResponse;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Sort sort = Sort.by("desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC, "startAt");
        Pageable pageable = PageRequest.of(page, size, sort);
        return eventService.listEvents(status, keyword, pageable);
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
