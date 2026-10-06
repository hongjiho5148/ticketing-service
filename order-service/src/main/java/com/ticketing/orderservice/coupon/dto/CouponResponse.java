package com.ticketing.orderservice.coupon.dto;

import com.ticketing.orderservice.coupon.Coupon;
import com.ticketing.orderservice.coupon.DiscountType;
import java.time.LocalDateTime;

public record CouponResponse(
        Long id,
        String code,
        DiscountType discountType,
        Integer discountValue,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        Integer maxUses,
        Integer usedCount) {

    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getCode(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getValidFrom(),
                coupon.getValidTo(),
                coupon.getMaxUses(),
                coupon.getUsedCount());
    }
}
