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
        // 대기열 입장은 봇이 자리를 잡으려고 마구 호출하는 것이므로, 푼 캡차가 있어야 한다.
        captchaVerifier.verify(captchaToken);
        return queueService.enter(SecurityUtil.getCurrentUserId(), request.eventId());
    }

    @GetMapping("/status")
    public QueueStatusResponse status(@RequestParam String queueToken) {
        return queueService.poll(queueToken);
    }

    /**
     * /status와 같은 답을 2초마다 묻는 대신 바뀔 때 밀어 준다. /status는 남는다. 스트림이 거절됐거나 끊긴 클라이언트를
     * 위한 대체 경로다.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String queueToken, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no"); // nginx: 이벤트를 버퍼에 붙잡아 두지 않는다
        response.setHeader("Cache-Control", "no-cache");
        return queueStreams.open(queueToken);
    }
}
