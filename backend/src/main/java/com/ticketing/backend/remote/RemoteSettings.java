package com.ticketing.backend.remote;

import java.time.Duration;

/** 다른 서비스를 호출할 때의 동작 방식(타임아웃, 서킷 브레이커, 재시도)이다. RemoteService 참고. */
public record RemoteSettings(
        Duration connectTimeout,
        Duration readTimeout,
        int windowSize,
        int minimumCalls,
        float failureRatePercent,
        Duration openWait,
        int halfOpenCalls,
        Duration retryBackoff) {}
