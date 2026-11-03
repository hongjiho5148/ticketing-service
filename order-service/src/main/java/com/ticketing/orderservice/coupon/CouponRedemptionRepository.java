package com.ticketing.orderservice.coupon;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    /** 지운 행의 수를 돌려준다. 0이면 이 주문이 쿠폰을 쓴 적이 없다는 뜻이다(또는 이미 돌려줬다). */
    long deleteByCouponIdAndUserIdAndOrderId(Long couponId, Long userId, Long orderId);

    List<CouponRedemption> findTop50ByUserIdOrderByIdDesc(Long userId);
}
