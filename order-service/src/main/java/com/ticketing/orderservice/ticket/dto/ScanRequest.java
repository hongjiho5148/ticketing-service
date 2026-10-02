package com.ticketing.orderservice.ticket.dto;

import jakarta.validation.constraints.NotBlank;

public record ScanRequest(@NotBlank String token) {
}
