package com.ticketing.orderservice.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType discountType;

    /** Won off for FLAT, whole percent (1-100) for PERCENT. */
    @Column(nullable = false)
    private Integer discountValue;

    @Column(nullable = false)
    private LocalDateTime validFrom;

    @Column(nullable = false)
    private LocalDateTime validTo;

    @Column(nullable = false)
    private Integer maxUses;

    // Only ever bumped through CouponRepository.markUsed (one atomic UPDATE ... WHERE usedCount <
    // maxUses), never by setting this field - two simultaneous payments must not both take the last use.
    @Column(nullable = false)
    private Integer usedCount;

    public Coupon(
            String code,
            DiscountType discountType,
            Integer discountValue,
            LocalDateTime validFrom,
            LocalDateTime validTo,
            Integer maxUses) {
        this.code = code;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.maxUses = maxUses;
        this.usedCount = 0;
    }

    public boolean isUsableAt(LocalDateTime now) {
        return !now.isBefore(validFrom) && !now.isAfter(validTo) && usedCount < maxUses;
    }

    public int discountFor(int price) {
        int raw = discountType == DiscountType.FLAT ? discountValue : (int) ((long) price * discountValue / 100);
        return Math.min(raw, price);
    }
}
