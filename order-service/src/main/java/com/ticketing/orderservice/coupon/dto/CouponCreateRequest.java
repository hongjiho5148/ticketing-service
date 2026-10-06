package com.ticketing.orderservice.coupon.dto;

import com.ticketing.orderservice.coupon.DiscountType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;

public record CouponCreateRequest(
        @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$", message = "쿠폰 코드는 영문/숫자/-/_ 3~30자여야 해요.") String code,
        @NotNull DiscountType discountType,
        @NotNull @Min(1) Integer discountValue,
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validTo,
        @NotNull @Min(1) @Max(1_000_000) Integer maxUses) {
}
