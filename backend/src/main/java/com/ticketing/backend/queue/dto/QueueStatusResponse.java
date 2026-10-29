package com.ticketing.backend.queue.dto;

/**
 * Where a person stands. For someone who has been let in, passExpiresInSeconds is how long the pass has left as of this
 * answer (seconds from now rather than a clock time, so the browser does not have to trust its own clock against the
 * server's).
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
