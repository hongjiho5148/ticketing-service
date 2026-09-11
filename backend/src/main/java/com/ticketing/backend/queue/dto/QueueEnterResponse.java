package com.ticketing.backend.queue.dto;

public record QueueEnterResponse(String queueToken, long rankNo, long estimatedWaitSeconds) {
}
