package com.ticketing.eventservice.internal.dto;

import com.ticketing.eventservice.event.Event;

public record EventExistsResponse(Long id, String title) {

    public static EventExistsResponse from(Event event) {
        return new EventExistsResponse(event.getId(), event.getTitle());
    }
}
