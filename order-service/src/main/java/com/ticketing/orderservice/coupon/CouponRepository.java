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
     * 이 사용자가 지금 적용할 수 있는 쿠폰이다. 유효 기간 안이고, 남은 횟수가 있고, 이 사용자가
     * 아직 쓰지 않은 쿠폰이다. 코드를 적용할 때 findUsable이 적용하는 세 규칙과 같다.
     */
    @Query("select c from Coupon c where c.validFrom <= :now and c.validTo >= :now and c.usedCount < c.maxUses "
            + "and not exists (select 1 from CouponRedemption r where r.couponId = c.id and r.userId = :userId) "
            + "order by c.validTo asc, c.id asc")
    List<Coupon> findAvailableFor(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 한 번의 사용을 원자적으로 돌려주되 0 아래로 내려가지 않는다. 대응하는 사용 기록 행을 지운 뒤에만 호출한다. */
    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount - 1 WHERE c.id = :id AND c.usedCount > 0")
    int markUnused(@Param("id") Long id);

    /** 한 번의 사용을 원자적으로 가져간다. 갱신된 행이 0이면 쿠폰이 이미 모두 사용됐다는 뜻이다. */
    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 WHERE c.id = :id AND c.usedCount < c.maxUses")
    int markUsed(@Param("id") Long id);
}
