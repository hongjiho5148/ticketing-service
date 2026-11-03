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

    /** FLAT은 깎는 원화 금액, PERCENT는 정수 퍼센트(1~100)다. */
    @Column(nullable = false)
    private Integer discountValue;

    @Column(nullable = false)
    private LocalDateTime validFrom;

    @Column(nullable = false)
    private LocalDateTime validTo;

    @Column(nullable = false)
    private Integer maxUses;

    // CouponRepository.markUsed(usedCount < maxUses 조건의 UPDATE 한 번)로만 올리고 이 필드를 직접 바꾸지
    // 않는다. 동시에 결제하는 두 사람이 마지막 한 장을 함께 가져가면 안 된다.
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
