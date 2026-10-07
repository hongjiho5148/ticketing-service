package com.ticketing.orderservice.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.coupon.dto.AvailableCouponResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The list a customer sees must match what applying a code would accept. Runs against the compose MySQL
 * and rolls back, so the dev coupons already in the table are left alone (and are filtered out below by a
 * per-test code prefix). Run inside the compose network with DB_HOST=mysql, like the other order-service tests.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
@Transactional
class AvailableCouponsTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private CouponService couponService;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponRedemptionRepository redemptionRepository;
    @PersistenceContext private EntityManager em;

    private Long userId;
    private String prefix;

    @BeforeEach
    void setUp() {
        userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        prefix = "T" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

    private Coupon coupon(String suffix, LocalDateTime from, LocalDateTime to, int maxUses) {
        return couponRepository.save(new Coupon(prefix + suffix, DiscountType.PERCENT, 10, from, to, maxUses));
    }

    private List<String> mineAvailable() {
        em.flush();
        em.clear();
        return couponService.available(userId).stream()
                .map(AvailableCouponResponse::code)
                .filter(code -> code.startsWith(prefix))
                .toList();
    }

    @Test
    void listsOnlyCouponsTheUserCouldActuallyApply() {
        LocalDateTime now = LocalDateTime.now();
        coupon("ACTIVE", now.minusDays(1), now.plusDays(5), 10);
        coupon("EXPIRED", now.minusDays(10), now.minusDays(1), 10);
        coupon("FUTURE", now.plusDays(1), now.plusDays(10), 10);

        Coupon exhausted = coupon("FULL", now.minusDays(1), now.plusDays(5), 1);
        em.flush();
        assertThat(couponRepository.markUsed(exhausted.getId())).isEqualTo(1);

        Coupon mine = coupon("USED", now.minusDays(1), now.plusDays(5), 10);
        redemptionRepository.save(new CouponRedemption(mine.getId(), mine.getCode(), userId, 1L, 1000));

        assertThat(mineAvailable()).containsExactly(prefix + "ACTIVE");
    }

    @Test
    void someoneElsesRedemptionDoesNotHideTheCouponFromMe() {
        LocalDateTime now = LocalDateTime.now();
        Coupon shared = coupon("SHARED", now.minusDays(1), now.plusDays(5), 10);
        redemptionRepository.save(new CouponRedemption(shared.getId(), shared.getCode(), userId + 1, 1L, 1000));

        assertThat(mineAvailable()).containsExactly(prefix + "SHARED");
    }

    @Test
    void soonestToExpireComesFirst() {
        LocalDateTime now = LocalDateTime.now();
        coupon("LATE", now.minusDays(1), now.plusDays(30), 10);
        coupon("SOON", now.minusDays(1), now.plusDays(2), 10);
        coupon("MID", now.minusDays(1), now.plusDays(10), 10);

        assertThat(mineAvailable()).containsExactly(prefix + "SOON", prefix + "MID", prefix + "LATE");
    }

    @Test
    void everythingListedCanBeAppliedAndEverythingHiddenCannot() {
        LocalDateTime now = LocalDateTime.now();
        coupon("OK", now.minusDays(1), now.plusDays(5), 10);
        coupon("EXPIRED", now.minusDays(10), now.minusDays(1), 10);
        em.flush();
        em.clear();

        for (String code : mineAvailable()) {
            assertThat(couponService.findUsable(code, userId).getCode()).isEqualTo(code);
        }
        assertThatThrownBy(() -> couponService.findUsable(prefix + "EXPIRED", userId)).isInstanceOf(ApiException.class);
    }

    @Test
    void theCustomerViewHidesTheOperatorsNumbers() {
        LocalDateTime now = LocalDateTime.now();
        coupon("VIEW", now.minusDays(1), now.plusDays(5), 10);
        em.flush();
        em.clear();

        AvailableCouponResponse view = couponService.available(userId).stream()
                .filter(c -> c.code().equals(prefix + "VIEW"))
                .findFirst()
                .orElseThrow();
        assertThat(view.discountType()).isEqualTo(DiscountType.PERCENT);
        assertThat(view.discountValue()).isEqualTo(10);
        assertThat(view.validTo()).isNotNull();
        assertThat(AvailableCouponResponse.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("code", "discountType", "discountValue", "validTo");
    }
}
