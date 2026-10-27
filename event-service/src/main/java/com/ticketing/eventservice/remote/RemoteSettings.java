package com.ticketing.eventservice.remote;

import java.time.Duration;

/** How every call to another service behaves (timeouts, circuit breaker, retry); see RemoteService. */
public record RemoteSettings(
        Duration connectTimeout,
        Duration readTimeout,
        int windowSize,
        int minimumCalls,
        float failureRatePercent,
        Duration openWait,
        int halfOpenCalls,
        Duration retryBackoff) {}
