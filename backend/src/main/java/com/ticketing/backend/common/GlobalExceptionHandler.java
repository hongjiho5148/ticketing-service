package com.ticketing.backend.common;

import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Redis가 죽었거나 응답하지 않을 때. 처리하지 않으면 스택 트레이스와 함께 영문 모를 500이 나갔다(장애 시험에서 줄 서기
     * 요청의 약 90%가 그랬다). 서버가 "지금은 안 되니 잠시 후 다시"라고 분명히 말하는 503으로 바꾸고, 다시 시도할 시점을 알려
     * 준다(Retry-After). 요청마다 긴 스택 트레이스를 쓰면 장애 중에 로그가 폭주하므로 한 줄만 남긴다.
     */
    @ExceptionHandler(RedisException.class)
    public ResponseEntity<ErrorResponse> handleRedisUnavailable(RedisException e) {
        log.warn("Redis unavailable, answering 503: {}: {}", e.getClass().getSimpleName(), firstLine(e.getMessage()));
        return ResponseEntity.status(ErrorCode.QUEUE_UNAVAILABLE.getStatus())
                .header(HttpHeaders.RETRY_AFTER, "3")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(ErrorCode.QUEUE_UNAVAILABLE));
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        String line = message.lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        // 엔드포인트가 무엇을 만들어 내든 JSON으로 응답한다. 대기열 스트림(text/event-stream)에는 오류 본문을 위한 컨버터가 없어서,
        // 이 설정이 없으면 404와 503이 맨 500으로 나갔다.
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(e.getErrorCode()));
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
