package com.ticketing.eventservice.event.dto;

import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.event.EventStatus;
import java.time.LocalDateTime;
import java.util.List;

public record EventDetailResponse(
        Long id,
        String title,
        String venue,
        String description,
        EventCategory category,
        LocalDateTime startAt,
        LocalDateTime openAt,
        EventStatus status,
        String sourceUrl,
        Double averageRating,
        long reviewCount,
        List<SeatGradeSummary> seatSummary,
        List<SeatSectionSummary> sectionSummary) {

    public static EventDetailResponse of(
            Event event,
            RatingStats rating,
            List<SeatGradeSummary> seatSummary,
            List<SeatSectionSummary> sectionSummary) {
        return new EventDetailResponse(
                event.getId(),
                event.getTitle(),
                event.getVenue(),
                event.getDescription(),
                event.getCategory(),
                event.getStartAt(),
                event.getOpenAt(),
                event.getStatus(),
                event.getSourceUrl(),
                rating.averageRating(),
                rating.reviewCount(),
                seatSummary,
                sectionSummary);
    }
}
