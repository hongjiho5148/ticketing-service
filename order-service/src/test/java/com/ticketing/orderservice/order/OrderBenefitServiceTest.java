package com.ticketing.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.coupon.Coupon;
import com.ticketing.orderservice.coupon.CouponRedemptionRepository;
import com.ticketing.orderservice.coupon.CouponRepository;
import com.ticketing.orderservice.coupon.DiscountType;
import com.ticketing.orderservice.point.PointAccount;
import com.ticketing.orderservice.point.PointAccountRepository;
import com.ticketing.orderservice.point.PointService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs against the docker-compose MySQL and rolls everything back, so the atomic UPDATE queries
 * (coupon use limit, point balance) are exercised for real rather than mocked. The sweepers' crons
 * are switched off so a scheduled tick can't touch the dev data mid-test.
 *
 * The DB is reached as host "mysql", so run it inside the compose network - a host-installed MySQL
 * on :3306 can shadow the container's published port and the tests would hit that instead:
 * <pre>
 * docker run --rm --network ticketing-service_default -e DB_HOST=mysql \
 *   -v "$PWD/order-service:/workspace" -w /workspace eclipse-temurin:17-jdk \
 *   sh -c "tr -d '\r' &lt; gradlew &gt; g &amp;&amp; sh g test --no-daemon"
 * </pre>
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
@Transactional
class OrderBenefitServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private OrderBenefitService benefits;
    @Autowired private PointService points;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponRedemptionRepository redemptionRepository;
    @Autowired private PointAccountRepository accountRepository;
    @PersistenceContext private EntityManager em;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    }

    private Orders order(int price) {
        long id = SEQ.incrementAndGet();
        return orderRepository.save(new Orders(userId, id, id, LocalDateTime.now().plusDays(3), 1L, price));
    }

    private Coupon coupon(DiscountType type, int value, int maxUses) {
        return couponRepository.save(new Coupon(
                "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                type,
                value,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1),
                maxUses));
    }

    private void giveBalance(long amount) {
        accountRepository.saveAndFlush(new PointAccount(userId));
        accountRepository.credit(userId, amount);
        em.clear();
    }

    private long balance() {
        em.flush();
        em.clear();
        return points.balance(userId);
    }

    @Test
    void percentCouponAndPointsShrinkTheCharge() {
        giveBalance(20_000);
        Orders order = order(100_000);

        benefits.applyCoupon(order, coupon(DiscountType.PERCENT, 10, 5).getCode());
        assertThat(order.getDiscountAmount()).isEqualTo(10_000);
        assertThat(order.getTotalPrice()).isEqualTo(90_000);

        benefits.applyPoints(order, 5_000);
        assertThat(order.getTotalPrice()).isEqualTo(85_000);
        assertThat(order.originalPrice()).isEqualTo(100_000);

        // Setting points again replaces rather than stacks.
        benefits.applyPoints(order, 1_000);
        assertThat(order.getTotalPrice()).isEqualTo(89_000);

        benefits.removeCoupon(order);
        assertThat(order.getTotalPrice()).isEqualTo(99_000);
        assertThat(order.getCouponCode()).isNull();
    }

    @Test
    void benefitsNeverPushTheChargeBelowTheMinimum() {
        giveBalance(1_000_000);
        Orders order = order(100_000);

        benefits.applyCoupon(order, coupon(DiscountType.FLAT, 500_000, 5).getCode());
        assertThat(order.getTotalPrice()).isEqualTo(OrderBenefitService.MIN_PAYABLE_AMOUNT);

        assertThatThrownBy(() -> benefits.applyPoints(order, 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POINTS_INVALID));
    }

    @Test
    void pointsAreLimitedToTheBalance() {
        giveBalance(3_000);
        Orders order = order(100_000);

        assertThatThrownBy(() -> benefits.applyPoints(order, 3_001))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POINTS_INSUFFICIENT));
    }

    @Test
    void redeemConsumesCouponAndPointsAndEarnsTheReward() {
        giveBalance(10_000);
        Orders order = order(100_000);
        Coupon coupon = coupon(DiscountType.FLAT, 5_000, 5);
        benefits.applyCoupon(order, coupon.getCode());
        benefits.applyPoints(order, 4_000); // charged: 100,000 - 5,000 - 4,000 = 91,000

        benefits.redeem(order);

        // 10,000 - 4,000 spent + 1% of 91,000 (= 910) earned
        assertThat(balance()).isEqualTo(6_910);
        em.refresh(couponRepository.findById(coupon.getId()).orElseThrow());
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(redemptionRepository.existsByCouponIdAndUserId(coupon.getId(), userId)).isTrue();
    }

    private int usedCount(Coupon coupon) {
        em.flush();
        em.clear();
        return couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount();
    }

    @Test
    void aFullRefundGivesTheCouponBackSoItCanBeUsedAgain() {
        Orders order = order(100_000);
        Coupon coupon = coupon(DiscountType.FLAT, 5_000, 5);
        benefits.applyCoupon(order, coupon.getCode());
        benefits.redeem(order);
        assertThat(usedCount(coupon)).isEqualTo(1);

        benefits.reverse(order, 100);

        assertThat(usedCount(coupon)).isZero();
        assertThat(redemptionRepository.existsByCouponIdAndUserId(coupon.getId(), userId)).isFalse();
        Orders again = order(100_000);
        benefits.applyCoupon(again, coupon.getCode()); // would throw COUPON_ALREADY_USED if it hadn't come back
        assertThat(again.getDiscountAmount()).isEqualTo(5_000);
    }

    @Test
    void aPartialRefundKeepsTheCouponSpent() {
        Orders order = order(100_000);
        Coupon coupon = coupon(DiscountType.FLAT, 5_000, 5);
        benefits.applyCoupon(order, coupon.getCode());
        benefits.redeem(order);

        benefits.reverse(order, 70);

        assertThat(usedCount(coupon)).isEqualTo(1);
        assertThat(redemptionRepository.existsByCouponIdAndUserId(coupon.getId(), userId)).isTrue();
        Orders again = order(100_000);
        assertThatThrownBy(() -> benefits.applyCoupon(again, coupon.getCode()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COUPON_ALREADY_USED));
    }

    @Test
    void givingACouponBackTwiceNeverFreesAnotherUsersUse() {
        Coupon coupon = coupon(DiscountType.FLAT, 5_000, 5);
        Orders mine = order(100_000);
        benefits.applyCoupon(mine, coupon.getCode());
        benefits.redeem(mine);

        long id = SEQ.incrementAndGet();
        Orders someoneElses = orderRepository.save(new Orders(userId + 1, id, id, LocalDateTime.now().plusDays(3), 1L, 100_000));
        benefits.applyCoupon(someoneElses, coupon.getCode());
        benefits.redeem(someoneElses);
        assertThat(usedCount(coupon)).isEqualTo(2);

        benefits.reverse(mine, 100);
        benefits.reverse(mine, 100); // a repeated reversal finds no redemption row left and gives nothing back

        assertThat(usedCount(coupon)).isEqualTo(1);
        assertThat(redemptionRepository.existsByCouponIdAndUserId(coupon.getId(), userId + 1)).isTrue();
    }

    @Test
    void reversingAnOrderWithoutACouponLeavesCouponsAlone() {
        Orders order = order(100_000);
        benefits.redeem(order); // no coupon, no points

        benefits.reverse(order, 100); // must not fail on the missing coupon
    }

    @Test
    void aCouponCanOnlyBeUsedOncePerUser() {
        Orders first = order(100_000);
        Coupon coupon = coupon(DiscountType.FLAT, 5_000, 5);
        benefits.applyCoupon(first, coupon.getCode());
        benefits.redeem(first);

        Orders second = order(100_000);
        assertThatThrownBy(() -> benefits.applyCoupon(second, coupon.getCode()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COUPON_ALREADY_USED));
    }

    @Test
    void redeemGivesPointsBackWhenTheCouponRanOutInTheMeantime() {
        giveBalance(10_000);
        Orders order = order(100_000);
        Coupon coupon = coupon(DiscountType.FLAT, 5_000, 2);
        benefits.applyCoupon(order, coupon.getCode());
        benefits.applyPoints(order, 4_000);

        // Two other people take both remaining uses between "apply" and "paid".
        couponRepository.markUsed(coupon.getId());
        couponRepository.markUsed(coupon.getId());

        assertThatThrownBy(() -> benefits.redeem(order))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COUPON_NOT_USABLE));
        assertThat(balance()).isEqualTo(10_000);
    }

    @Test
    void reverseRestoresSpentPointsAndTakesBackTheReward() {
        giveBalance(10_000);
        Orders order = order(100_000);
        benefits.applyPoints(order, 4_000); // charged 96,000 -> reward 960
        benefits.redeem(order);
        assertThat(balance()).isEqualTo(10_000 - 4_000 + 960);

        benefits.reverse(order, 100);

        assertThat(balance()).isEqualTo(10_000);
    }

    @Test
    void aPartialRefundOnlyGivesBackThatShareOfThePointsSpent() {
        giveBalance(10_000);
        Orders order = order(100_000);
        benefits.applyPoints(order, 5_000); // charged 95,000 -> reward 950
        benefits.redeem(order);
        assertThat(balance()).isEqualTo(10_000 - 5_000 + 950);

        benefits.reverse(order, 70); // 70% of 5,000 = 3,500 back, reward fully taken back

        assertThat(balance()).isEqualTo(10_000 - 5_000 + 3_500);
    }

    @Test
    void rewardClawbackNeverGoesBelowZero() {
        Orders order = order(100_000); // no points used, reward 1,000
        benefits.redeem(order);
        assertThat(balance()).isEqualTo(1_000);

        // The reward was already spent elsewhere.
        accountRepository.deduct(userId, 900);
        em.clear();
        benefits.reverse(order, 100);

        assertThat(balance()).isZero();
    }
}
