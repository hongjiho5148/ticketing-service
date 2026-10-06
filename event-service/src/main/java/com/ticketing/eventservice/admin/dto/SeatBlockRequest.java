package com.ticketing.eventservice.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One visual block of seats: rows x seatsPerRow identical-grade/price seats under a single section name. */
public record SeatBlockRequest(
        @NotBlank @Size(max = 50) String section,
        @NotBlank @Size(max = 20) String grade,
        @NotNull @Min(0) Integer price,
        @NotNull @Min(1) @Max(100) Integer rows,
        @NotNull @Min(1) @Max(100) Integer seatsPerRow) {
}
