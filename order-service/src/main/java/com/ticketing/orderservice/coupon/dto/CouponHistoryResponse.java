package com.ticketing.orderservice.coupon.dto;

import com.ticketing.orderservice.coupon.CouponRedemption;
import java.time.LocalDateTime;

public record CouponHistoryResponse(String code, Integer discountAmount, Long orderId, LocalDateTime redeemedAt) {

    public static CouponHistoryResponse from(CouponRedemption redemption) {
        return new CouponHistoryResponse(
                redemption.getCouponCode(),
                redemption.getDiscountAmount(),
                redemption.getOrderId(),
                redemption.getRedeemedAt());
    }
}
