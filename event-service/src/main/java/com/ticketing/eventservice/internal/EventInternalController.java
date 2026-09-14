package com.ticketing.eventservice.internal;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.internal.dto.EventExistsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only - not routed through the public Gateway, reached directly within the docker network. */
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
