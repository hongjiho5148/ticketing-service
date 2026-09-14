package com.ticketing.eventservice.event.dto;

public record SeatSectionSummary(String section, String grade, Integer price, long totalCount, long availableCount) {
}
