package com.ticketing.backend.queue.dto;

/**
 * 한 사람이 서 있는 곳이다. 입장한 사람에게는 passExpiresInSeconds가 이 응답 시점에 입장권이 얼마나 남았는지다(시각이
 * 아니라 지금부터의 초라서, 브라우저가 자기 시계를 서버의 시계와 맞춰 믿을 필요가 없다).
 */
public record QueueStatusResponse(
        long rankNo, String status, Long estimatedWaitSeconds, String passToken, Long passExpiresInSeconds) {

    public static QueueStatusResponse waiting(long rankNo, long estimatedWaitSeconds) {
        return new QueueStatusResponse(rankNo, "WAITING", estimatedWaitSeconds, null, null);
    }

    public static QueueStatusResponse passed(String passToken, long passExpiresInSeconds) {
        return new QueueStatusResponse(0, "PASSED", null, passToken, passExpiresInSeconds);
    }
}
