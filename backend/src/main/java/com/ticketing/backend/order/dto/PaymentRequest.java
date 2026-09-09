package com.ticketing.backend.order.dto;

import jakarta.validation.constraints.NotBlank;

public record PaymentRequest(@NotBlank String method) {
}
