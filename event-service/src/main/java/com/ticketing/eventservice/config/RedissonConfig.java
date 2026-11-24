package com.ticketing.eventservice.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    /**
     * Redis가 죽었을 때 좌석 요청이 얼마나 오래 매달리는지는 아래 값이 정한다. Redisson 기본값(명령 타임아웃 3초, 재시도
     * 4번에 재시도 간격 1.5초)에서는 Redis가 멈추면 요청 하나가 약 7초 뒤에야 실패했다(장애 시험 기록은
     * docs/design-notes.md "Redis 장애 시험" 참고). 지금은 대략 1~2초 안에 실패한다.
     *
     * <p>너무 짧으면 Redis가 멀쩡하게 느린 순간에도 실패로 오해할 수 있어서, 평소 응답(1ms 미만)에는 넉넉하고 장애에는 빠른
     * 값으로 골랐다. 값은 application.yml에 있고(환경변수로 바꿀 수 있다) 코드에는 기본값을 두지 않는다. 기본값이 코드와 설정 두 곳에 있으면 한쪽만
     * 바뀌어도 알아채지 못한다.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${redis.timeout-ms}") int timeoutMs,
            @Value("${redis.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${redis.retry-attempts}") int retryAttempts,
            @Value("${redis.retry-interval-ms}") int retryIntervalMs) {
        return Redisson.create(buildConfig(host, port, timeoutMs, connectTimeoutMs, retryAttempts, retryIntervalMs));
    }

    /** 연결하지 않고 설정만 만든다(값이 제대로 들어갔는지 테스트하려고 분리). */
    static Config buildConfig(
            String host, int port, int timeoutMs, int connectTimeoutMs, int retryAttempts, int retryIntervalMs) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setTimeout(timeoutMs)
                .setConnectTimeout(connectTimeoutMs)
                .setRetryAttempts(retryAttempts)
                .setRetryInterval(retryIntervalMs);
        return config;
    }
}
