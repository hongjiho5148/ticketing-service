package com.ticketing.backend.queue;

import com.ticketing.backend.auth.SecurityUtil;
import com.ticketing.backend.queue.dto.QueueEnterRequest;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/enter")
    public QueueEnterResponse enter(@Valid @RequestBody QueueEnterRequest request) {
        return queueService.enter(SecurityUtil.getCurrentUserId(), request.eventId());
    }

    @GetMapping("/status")
    public QueueStatusResponse status(@RequestParam String queueToken) {
        return queueService.status(queueToken);
    }
}
