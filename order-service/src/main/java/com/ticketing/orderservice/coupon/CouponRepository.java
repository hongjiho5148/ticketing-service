package com.ticketing.orderservice.coupon;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Coupons this user could apply right now: inside their validity window, with uses left, and not
     * already redeemed by this user. The same three rules findUsable enforces when a code is applied.
     */
    @Query("select c from Coupon c where c.validFrom <= :now and c.validTo >= :now and c.usedCount < c.maxUses "
            + "and not exists (select 1 from CouponRedemption r where r.couponId = c.id and r.userId = :userId) "
            + "order by c.validTo asc, c.id asc")
    List<Coupon> findAvailableFor(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** Gives one use back atomically, never below zero. Only called after the redemption row it belongs to was deleted. */
    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount - 1 WHERE c.id = :id AND c.usedCount > 0")
    int markUnused(@Param("id") Long id);

    /** Takes one use atomically; 0 rows updated means the coupon was already fully used. */
    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 WHERE c.id = :id AND c.usedCount < c.maxUses")
    int markUsed(@Param("id") Long id);
}
