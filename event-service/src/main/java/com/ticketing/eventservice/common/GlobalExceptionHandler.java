package com.ticketing.eventservice.common;

import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus()).body(ErrorResponse.of(e.getErrorCode()));
    }

    /**
     * 좌석 락을 잡으려는데 Redis가 죽었거나 응답하지 않을 때. 처리하지 않으면 스택 트레이스와 함께 영문 모를 500이 나갔다.
     * 서버가 "지금은 안 되니 잠시 후 다시"라고 분명히 말하는 503으로 바꾼다. 락을 못 잡았으니 좌석은 건드리지 않았고, 이 요청은
     * 안전하게 다시 시도할 수 있다. 요청마다 긴 스택 트레이스를 쓰면 장애 중에 로그가 폭주하므로 한 줄만 남긴다.
     */
    @ExceptionHandler(RedisException.class)
    public ResponseEntity<ErrorResponse> handleRedisUnavailable(RedisException e) {
        log.warn("Redis unavailable, answering 503: {}: {}", e.getClass().getSimpleName(), firstLine(e.getMessage()));
        return ResponseEntity.status(ErrorCode.SEAT_LOCK_UNAVAILABLE.getStatus())
                .header(HttpHeaders.RETRY_AFTER, "3")
                .body(ErrorResponse.of(ErrorCode.SEAT_LOCK_UNAVAILABLE));
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        String line = message.lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    /** 분리 전에 backend가 처리하던 것과 같은 좌석 경합이다. SeatLockService 참고. */
    @ExceptionHandler({
        ObjectOptimisticLockingFailureException.class,
        DataIntegrityViolationException.class,
        PessimisticLockingFailureException.class
    })
    public ResponseEntity<ErrorResponse> handleSeatContention(Exception e) {
        return ResponseEntity.status(ErrorCode.SEAT_ALREADY_RESERVED.getStatus())
                .body(ErrorResponse.of(ErrorCode.SEAT_ALREADY_RESERVED));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
    }
}
