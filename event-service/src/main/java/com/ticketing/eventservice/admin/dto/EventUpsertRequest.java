package com.ticketing.eventservice.admin.dto;

import com.ticketing.eventservice.event.EventStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record EventUpsertRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 200) String venue,
        @Size(max = 5000) String description,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime openAt,
        @NotNull EventStatus status) {
}
