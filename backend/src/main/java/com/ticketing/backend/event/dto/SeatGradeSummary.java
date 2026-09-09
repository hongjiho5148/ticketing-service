package com.ticketing.backend.event.dto;

public record SeatGradeSummary(String grade, long totalCount, long availableCount) {
}
