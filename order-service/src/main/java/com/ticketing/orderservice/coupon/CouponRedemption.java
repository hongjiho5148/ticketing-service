package com.ticketing.orderservice.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 쿠폰을 쓴 주문이 실제로 결제될 때 기록한다. (쿠폰, 사용자)당 한 행이라 코드는 1인 1회만 쓸 수 있다. */
@Entity
@Table(
        name = "coupon_redemption",
        uniqueConstraints = @UniqueConstraint(name = "uk_redemption_coupon_user", columnNames = {"coupon_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    // 쿠폰 테이블을 다시 조인하지 않고도 사용자의 쿠폰 내역을 보이도록 값을 복사해 둔다.
    @Column(nullable = false, length = 30)
    private String couponCode;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Integer discountAmount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime redeemedAt;

    public CouponRedemption(Long couponId, String couponCode, Long userId, Long orderId, Integer discountAmount) {
        this.couponId = couponId;
        this.couponCode = couponCode;
        this.userId = userId;
        this.orderId = orderId;
        this.discountAmount = discountAmount;
    }
}
