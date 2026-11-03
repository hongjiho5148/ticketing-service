package com.ticketing.eventservice.event;

import com.ticketing.eventservice.event.dto.EventDetailResponse;
import com.ticketing.eventservice.event.dto.EventListResponse;
import com.ticketing.eventservice.seat.dto.SeatResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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
            @RequestParam(required = false) EventCategory category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Sort sort = Sort.by("desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC, "startAt");
        Pageable pageable = PageRequest.of(page, size, sort);
        // endDate는 호출자 입장에서는 그 날을 포함한다(달력의 한 달은 마지막 날에 끝난다). 서비스의 구간은 끝을 포함하지 않는다.
        return eventService.listEvents(
                status, category, keyword, startDate, endDate == null ? null : endDate.plusDays(1), pageable);
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
