package com.ticketing.backend.event.dto;

import com.ticketing.backend.event.Event;
import com.ticketing.backend.event.EventStatus;
import java.time.LocalDateTime;
import java.util.List;

public record EventDetailResponse(
        Long id,
        String title,
        String venue,
        String description,
        LocalDateTime startAt,
        EventStatus status,
        List<SeatGradeSummary> seatSummary,
        List<SeatSectionSummary> sectionSummary) {

    public static EventDetailResponse of(
            Event event, List<SeatGradeSummary> seatSummary, List<SeatSectionSummary> sectionSummary) {
        return new EventDetailResponse(
                event.getId(),
                event.getTitle(),
                event.getVenue(),
                event.getDescription(),
                event.getStartAt(),
                event.getStatus(),
                seatSummary,
                sectionSummary);
    }
}
