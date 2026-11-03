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
 * docker-compose의 MySQL에서 실행하고 모든 것을 롤백한다. 그래서 원자적 UPDATE 쿼리(쿠폰 사용 한도,
 * 포인트 잔액)를 mock 없이 실제로 실행해 본다. 스위퍼의 크론은 꺼 두어서 예약된 주기가 테스트 도중
 * 개발 데이터를 건드리지 못하게 한다.
 *
 * DB는 호스트 이름 "mysql"로 접근하므로 compose 네트워크 안에서 실행해야 한다. 호스트에 설치된 MySQL이
 * :3306을 가로채면 컨테이너가 열어 둔 포트 대신 그쪽으로 붙어 버린다.
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

        // 포인트를 다시 설정하면 쌓이는 것이 아니라 교체된다.
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
        benefits.applyPoints(order, 4_000); // 청구액: 100,000 - 5,000 - 4,000 = 91,000

        benefits.redeem(order);

        // 10,000 - 사용 4,000 + 91,000의 1%(= 910) 적립
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
        benefits.applyCoupon(again, coupon.getCode()); // 돌아오지 않았다면 COUPON_ALREADY_USED를 던졌을 것이다
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
        benefits.reverse(mine, 100); // 반복된 되돌림은 남은 사용 기록 행이 없어서 아무것도 돌려주지 않는다

        assertThat(usedCount(coupon)).isEqualTo(1);
        assertThat(redemptionRepository.existsByCouponIdAndUserId(coupon.getId(), userId + 1)).isTrue();
    }

    @Test
    void reversingAnOrderWithoutACouponLeavesCouponsAlone() {
        Orders order = order(100_000);
        benefits.redeem(order); // 쿠폰 없음, 포인트 없음

        benefits.reverse(order, 100); // 없는 쿠폰 때문에 실패하면 안 된다
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

        // "적용"과 "결제" 사이에 다른 두 사람이 남은 두 번의 사용을 모두 가져간다.
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
        benefits.applyPoints(order, 4_000); // 청구 96,000 -> 적립 960
        benefits.redeem(order);
        assertThat(balance()).isEqualTo(10_000 - 4_000 + 960);

        benefits.reverse(order, 100);

        assertThat(balance()).isEqualTo(10_000);
    }

    @Test
    void aPartialRefundOnlyGivesBackThatShareOfThePointsSpent() {
        giveBalance(10_000);
        Orders order = order(100_000);
        benefits.applyPoints(order, 5_000); // 청구 95,000 -> 적립 950
        benefits.redeem(order);
        assertThat(balance()).isEqualTo(10_000 - 5_000 + 950);

        benefits.reverse(order, 70); // 5,000의 70% = 3,500 환불, 적립은 전부 회수

        assertThat(balance()).isEqualTo(10_000 - 5_000 + 3_500);
    }

    @Test
    void rewardClawbackNeverGoesBelowZero() {
        Orders order = order(100_000); // 포인트 사용 없음, 적립 1,000
        benefits.redeem(order);
        assertThat(balance()).isEqualTo(1_000);

        // 적립은 이미 다른 곳에 썼다.
        accountRepository.deduct(userId, 900);
        em.clear();
        benefits.reverse(order, 100);

        assertThat(balance()).isZero();
    }
}
