package com.ticketing.eventservice.kopis;

import java.time.LocalDate;

/** One performance as KOPIS describes it. List responses only fill id/title/period/venue/genre; detail fills the rest. */
public record KopisPerformance(
        String id,
        String title,
        LocalDate from,
        LocalDate to,
        String venue,
        String genreName,
        String cast,
        String runtime,
        String age,
        String priceGuidance,
        String scheduleGuidance,
        String synopsis) {
}
