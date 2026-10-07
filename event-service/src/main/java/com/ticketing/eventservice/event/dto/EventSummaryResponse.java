package com.ticketing.eventservice.event.dto;

import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.event.EventStatus;
import java.time.LocalDateTime;

public record EventSummaryResponse(
        Long id,
        String title,
        String venue,
        EventCategory category,
        LocalDateTime startAt,
        LocalDateTime openAt,
        EventStatus status,
        Double averageRating,
        long reviewCount) {

    public static EventSummaryResponse from(Event event) {
        return from(event, RatingStats.NONE);
    }

    public static EventSummaryResponse from(Event event, RatingStats rating) {
        return new EventSummaryResponse(
                event.getId(),
                event.getTitle(),
                event.getVenue(),
                event.getCategory(),
                event.getStartAt(),
                event.getOpenAt(),
                event.getStatus(),
                rating.averageRating(),
                rating.reviewCount());
    }
}
