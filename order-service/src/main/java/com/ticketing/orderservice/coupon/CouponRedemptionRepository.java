package com.ticketing.orderservice.coupon;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    /** Returns how many rows it removed - 0 means this order never redeemed the coupon (or it was already given back). */
    long deleteByCouponIdAndUserIdAndOrderId(Long couponId, Long userId, Long orderId);

    List<CouponRedemption> findTop50ByUserIdOrderByIdDesc(Long userId);
}
