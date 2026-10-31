package com.ticketing.eventservice.internal.dto;

import com.ticketing.eventservice.event.Event;
import java.time.LocalDateTime;

/** salesClosed: whether booking for this show is over (judged from the clock, see Event.isSalesClosed). */
public record EventExistsResponse(Long id, String title, boolean salesClosed) {

    public static EventExistsResponse from(Event event) {
        return new EventExistsResponse(event.getId(), event.getTitle(), event.isSalesClosed(LocalDateTime.now()));
    }
}
