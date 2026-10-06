package com.ticketing.orderservice.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApplyCouponRequest(@NotBlank @Size(max = 30) String code) {
}
