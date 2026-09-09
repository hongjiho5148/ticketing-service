package com.ticketing.backend.event.dto;

import com.ticketing.backend.event.Event;
import com.ticketing.backend.event.EventStatus;
import java.time.LocalDateTime;
import java.util.List;

public record EventDetailResponse(
        Long id, String title, String venue, LocalDateTime startAt, EventStatus status, List<SeatGradeSummary> seatSummary) {

    public static EventDetailResponse of(Event event, List<SeatGradeSummary> seatSummary) {
        return new EventDetailResponse(
                event.getId(), event.getTitle(), event.getVenue(), event.getStartAt(), event.getStatus(), seatSummary);
    }
}
