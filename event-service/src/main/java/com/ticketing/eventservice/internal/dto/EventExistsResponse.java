package com.ticketing.eventservice.internal.dto;

import com.ticketing.eventservice.event.Event;
import java.time.LocalDateTime;

/** salesClosed: 이 공연의 예매가 끝났는지(시계로 판정, Event.isSalesClosed 참고). */
public record EventExistsResponse(Long id, String title, boolean salesClosed) {

    public static EventExistsResponse from(Event event) {
        return new EventExistsResponse(event.getId(), event.getTitle(), event.isSalesClosed(LocalDateTime.now()));
    }
}
