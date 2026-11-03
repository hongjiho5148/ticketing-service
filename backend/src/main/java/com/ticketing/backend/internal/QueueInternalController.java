package com.ticketing.backend.internal;

import com.ticketing.backend.internal.dto.PassTokenValidResponse;
import com.ticketing.backend.queue.QueueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 사이 호출 전용이다. 공개 게이트웨이를 거치지 않고 docker 네트워크 안에서 직접 닿는다.
 * 이 단계에서는 대기열을 분리하지 않았으므로, reservation-service가 좌석을 잡기 전에 입장권 토큰을
 * 검증하려고 여기를 다시 호출한다.
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
