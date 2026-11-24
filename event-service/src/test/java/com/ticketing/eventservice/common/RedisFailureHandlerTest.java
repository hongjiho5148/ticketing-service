package com.ticketing.eventservice.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Redis 오류가 영문 모를 500이 아니라 안내가 담긴 503으로 나가는지 검사한다(Spring 없이 처리기만 직접 부른다). */
class RedisFailureHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aRedisTimeoutBecomesA503WithAMessageAndARetryHint() {
        ResponseEntity<ErrorResponse> response =
                handler.handleRedisUnavailable(new RedisTimeoutException("Unable to acquire connection!\nIncrease connection pool size."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("SEAT_LOCK_UNAVAILABLE");
        assertThat(response.getBody().message()).contains("잠시 후 다시 시도");
    }

    @Test
    void aRefusedConnectionIsTreatedTheSameWay() {
        ResponseEntity<ErrorResponse> response = handler.handleRedisUnavailable(new RedisConnectionException("connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void theMessageDoesNotLeakInternalDetails() {
        ResponseEntity<ErrorResponse> response =
                handler.handleRedisUnavailable(new RedisTimeoutException("NodeSource [slot=0] command: (HEXISTS), params: [lock:seat:17639]"));

        assertThat(response.getBody().message()).doesNotContain("lock:seat").doesNotContain("HEXISTS").doesNotContain("Redis");
    }
}
