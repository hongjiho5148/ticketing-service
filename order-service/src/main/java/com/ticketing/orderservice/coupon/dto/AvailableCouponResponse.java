package com.ticketing.orderservice.coupon.dto;

import com.ticketing.orderservice.coupon.Coupon;
import com.ticketing.orderservice.coupon.DiscountType;
import java.time.LocalDateTime;

/** 고객이 쓸 수 있는 쿠폰에 대해 알아야 하는 정보다. 사용 한도와 카운터는 운영자의 사정이라 일부러 뺐다. */
public record AvailableCouponResponse(String code, DiscountType discountType, Integer discountValue, LocalDateTime validTo) {

    public static AvailableCouponResponse from(Coupon coupon) {
        return new AvailableCouponResponse(
                coupon.getCode(), coupon.getDiscountType(), coupon.getDiscountValue(), coupon.getValidTo());
    }
}
