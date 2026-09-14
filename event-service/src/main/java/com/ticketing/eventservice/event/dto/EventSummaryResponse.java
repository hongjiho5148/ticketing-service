package com.ticketing.eventservice.event.dto;

import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventStatus;
import java.time.LocalDateTime;

public record EventSummaryResponse(
        Long id, String title, String venue, LocalDateTime startAt, LocalDateTime openAt, EventStatus status) {

    public static EventSummaryResponse from(Event event) {
        return new EventSummaryResponse(
                event.getId(), event.getTitle(), event.getVenue(), event.getStartAt(), event.getOpenAt(), event.getStatus());
    }
}
