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

/** Written when an order using a coupon is actually paid - one row per (coupon, user), so a code is single-use per person. */
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

    // Denormalized so the user's coupon history doesn't need a join back to the coupon table.
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
