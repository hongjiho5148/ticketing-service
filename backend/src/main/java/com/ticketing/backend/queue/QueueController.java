package com.ticketing.backend.queue;

import com.ticketing.backend.auth.SecurityUtil;
import com.ticketing.backend.captcha.CaptchaVerifier;
import com.ticketing.backend.queue.dto.QueueEnterRequest;
import com.ticketing.backend.queue.dto.QueueEnterResponse;
import com.ticketing.backend.queue.dto.QueueStatusResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;
    private final QueueStreams queueStreams;
    private final CaptchaVerifier captchaVerifier;

    public QueueController(QueueService queueService, QueueStreams queueStreams, CaptchaVerifier captchaVerifier) {
        this.queueService = queueService;
        this.queueStreams = queueStreams;
        this.captchaVerifier = captchaVerifier;
    }

    @PostMapping("/enter")
    public QueueEnterResponse enter(
            @Valid @RequestBody QueueEnterRequest request,
            @RequestHeader(value = "X-Captcha-Token", required = false) String captchaToken) {
        // Entering the queue is what bots spam to grab a spot, so it is gated on a solved captcha.
        captchaVerifier.verify(captchaToken);
        return queueService.enter(SecurityUtil.getCurrentUserId(), request.eventId());
    }

    @GetMapping("/status")
    public QueueStatusResponse status(@RequestParam String queueToken) {
        return queueService.status(queueToken);
    }

    /**
     * The same answer as /status, pushed when it changes instead of asked for every two seconds. /status stays: it is
     * the fallback for a client whose stream is refused or cut.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String queueToken, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no"); // nginx: do not hold the events back in a buffer
        response.setHeader("Cache-Control", "no-cache");
        return queueStreams.open(queueToken);
    }
}
