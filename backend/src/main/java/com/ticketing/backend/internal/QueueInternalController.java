package com.ticketing.backend.internal;

import com.ticketing.backend.internal.dto.PassTokenValidResponse;
import com.ticketing.backend.queue.QueueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service only - not routed through the public Gateway, reached directly within the
 * docker network. Queue wasn't split out in this phase, so reservation-service calls back here to
 * validate a pass token before it holds a seat.
 */
@RestController
@RequestMapping("/internal/queue")
public class QueueInternalController {

    private final QueueService queueService;

    public QueueInternalController(QueueService queueService) {
        this.queueService = queueService;
    }

    @GetMapping("/pass-token/valid")
    public PassTokenValidResponse isPassTokenValid(
            @RequestParam Long eventId, @RequestParam(required = false) String passToken) {
        return new PassTokenValidResponse(queueService.isPassTokenValid(eventId, passToken));
    }
}
