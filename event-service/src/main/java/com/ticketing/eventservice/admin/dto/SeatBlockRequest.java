package com.ticketing.eventservice.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 좌석의 시각적 블록 하나다. 하나의 구역 이름 아래에 등급과 가격이 같은 좌석이 행 수 x 행당 좌석 수만큼 있다. */
public record SeatBlockRequest(
        @NotBlank @Size(max = 50) String section,
        @NotBlank @Size(max = 20) String grade,
        @NotNull @Min(0) Integer price,
        @NotNull @Min(1) @Max(100) Integer rows,
        @NotNull @Min(1) @Max(100) Integer seatsPerRow) {
}
