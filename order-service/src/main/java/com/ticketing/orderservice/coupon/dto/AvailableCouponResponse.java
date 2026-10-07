package com.ticketing.orderservice.coupon.dto;

import com.ticketing.orderservice.coupon.Coupon;
import com.ticketing.orderservice.coupon.DiscountType;
import java.time.LocalDateTime;

/** What a customer needs to know about a coupon they can use - deliberately without the use limit and counter, which are the operator's business. */
public record AvailableCouponResponse(String code, DiscountType discountType, Integer discountValue, LocalDateTime validTo) {

    public static AvailableCouponResponse from(Coupon coupon) {
        return new AvailableCouponResponse(
                coupon.getCode(), coupon.getDiscountType(), coupon.getDiscountValue(), coupon.getValidTo());
    }
}
