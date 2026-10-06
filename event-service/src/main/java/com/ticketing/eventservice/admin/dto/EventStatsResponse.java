package com.ticketing.eventservice.admin.dto;

import java.util.List;

public record EventStatsResponse(
        Long eventId,
        String title,
        long totalSeats,
        long availableSeats,
        long holdSeats,
        long soldSeats,
        long soldSeatRevenue,
        List<GradeStats> grades) {

    public record GradeStats(String grade, long total, long available, long hold, long sold) {
    }
}
