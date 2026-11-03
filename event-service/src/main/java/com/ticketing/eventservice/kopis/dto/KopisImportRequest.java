package com.ticketing.eventservice.kopis.dto;

import com.ticketing.eventservice.kopis.KopisGenre;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** {@code from}/{@code to}는 찾는 공연 기간의 범위다(기본: 오늘 .. +90일). {@code limit}는 새 공연의 수를 제한한다(기본 10). */
public record KopisImportRequest(
        @NotNull KopisGenre genre, LocalDate from, LocalDate to, @Min(1) @Max(30) Integer limit) {
}
