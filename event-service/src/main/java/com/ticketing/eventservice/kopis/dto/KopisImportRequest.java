package com.ticketing.eventservice.kopis.dto;

import com.ticketing.eventservice.kopis.KopisGenre;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** {@code from}/{@code to} bound the performance period searched (default: today .. +90 days); {@code limit} caps new events (default 10). */
public record KopisImportRequest(
        @NotNull KopisGenre genre, LocalDate from, LocalDate to, @Min(1) @Max(30) Integer limit) {
}
