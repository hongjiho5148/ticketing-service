package com.ticketing.eventservice.kopis;

import java.time.LocalDate;

/** KOPIS가 설명하는 공연 하나다. 목록 응답은 id/제목/기간/장소/장르만 채우고, 나머지는 상세가 채운다. */
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
