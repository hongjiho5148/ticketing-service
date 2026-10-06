package com.ticketing.orderservice.coupon;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    List<CouponRedemption> findTop50ByUserIdOrderByIdDesc(Long userId);
}
