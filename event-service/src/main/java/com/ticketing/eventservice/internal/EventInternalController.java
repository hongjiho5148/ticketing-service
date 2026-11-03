package com.ticketing.eventservice.internal;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.internal.dto.EventExistsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 사이 호출 전용이다. 공개 게이트웨이를 거치지 않고 docker 네트워크 안에서 직접 닿는다. */
@RestController
@RequestMapping("/internal/events")
public class EventInternalController {

    private final EventRepository eventRepository;

    public EventInternalController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping("/{eventId}")
    public EventExistsResponse getEvent(@PathVariable Long eventId) {
        return eventRepository
                .findById(eventId)
                .map(EventExistsResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
    }
}
