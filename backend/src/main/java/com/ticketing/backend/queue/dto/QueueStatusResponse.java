package com.ticketing.backend.queue.dto;

public record QueueStatusResponse(long rankNo, String status, Long estimatedWaitSeconds, String passToken) {

    public static QueueStatusResponse waiting(long rankNo, long estimatedWaitSeconds) {
        return new QueueStatusResponse(rankNo, "WAITING", estimatedWaitSeconds, null);
    }

    public static QueueStatusResponse passed(String passToken) {
        return new QueueStatusResponse(0, "PASSED", null, passToken);
    }
}
