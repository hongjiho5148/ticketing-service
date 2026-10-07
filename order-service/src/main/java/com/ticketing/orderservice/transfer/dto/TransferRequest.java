package com.ticketing.orderservice.transfer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransferRequest(@NotBlank @Email @Size(max = 200) String email) {
}
