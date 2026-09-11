package com.ticketing.backend.queue.dto;

import jakarta.validation.constraints.NotNull;

public record QueueEnterRequest(@NotNull Long eventId) {
}
