package com.ticketing.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RefundPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    private static Orders orderStartingIn(Duration lead, int price, int pointsUsed) {
        Orders order = new Orders(1L, 1L, 1L, NOW.plus(lead), 1L, price + pointsUsed);
        order.applyBenefits(null, 0, pointsUsed); // charged = price, points = pointsUsed
        return order;
    }

    private static RefundQuote quote(Duration lead) {
        return RefundPolicy.quote(orderStartingIn(lead, 100_000, 0), NOW);
    }

    @Test
    void aCouponIsGivenBackOnlyWithAFullRefund() {
        Orders full = orderStartingIn(Duration.ofDays(30), 100_000, 0);
        full.applyBenefits("WELCOME10", 10_000, 0);
        RefundQuote fullQuote = RefundPolicy.quote(full, NOW);
        assertThat(fullQuote.couponRestored()).isTrue();
        assertThat(fullQuote.couponForfeited()).isFalse();

        Orders partial = orderStartingIn(Duration.ofDays(2), 100_000, 0);
        partial.applyBenefits("WELCOME10", 10_000, 0);
        RefundQuote partialQuote = RefundPolicy.quote(partial, NOW);
        assertThat(partialQuote.couponRestored()).isFalse();
        assertThat(partialQuote.couponForfeited()).isTrue();

        RefundQuote noCoupon = quote(Duration.ofDays(30));
        assertThat(noCoupon.couponRestored()).isFalse();
        assertThat(noCoupon.couponForfeited()).isFalse();

        Orders tooLate = orderStartingIn(Duration.ofHours(5), 100_000, 0);
        tooLate.applyBenefits("WELCOME10", 10_000, 0);
        RefundQuote lateQuote = RefundPolicy.quote(tooLate, NOW);
        assertThat(lateQuote.couponRestored()).isFalse();
        assertThat(lateQuote.couponForfeited()).isFalse(); // nothing is cancelled, so nothing is forfeited
    }

    @Test
    void fullRefundAWeekOrMoreAhead() {
        assertThat(quote(Duration.ofDays(30)).refundPercent()).isEqualTo(100);
        assertThat(quote(Duration.ofDays(7)).refundPercent()).isEqualTo(100);
    }

    @Test
    void seventyPercentBetweenThreeAndSevenDays() {
        assertThat(quote(Duration.ofDays(7).minusSeconds(1)).refundPercent()).isEqualTo(70);
        assertThat(quote(Duration.ofDays(3)).refundPercent()).isEqualTo(70);
    }

    @Test
    void thirtyPercentBetweenOneAndThreeDays() {
        assertThat(quote(Duration.ofDays(3).minusSeconds(1)).refundPercent()).isEqualTo(30);
        assertThat(quote(Duration.ofDays(1)).refundPercent()).isEqualTo(30);
    }

    @Test
    void noCancellationInsideTheLastDay() {
        RefundQuote lastDay = quote(Duration.ofDays(1).minusSeconds(1));
        assertThat(lastDay.refundPercent()).isZero();
        assertThat(lastDay.isCancellable()).isFalse();
        assertThat(quote(Duration.ofHours(-1)).isCancellable()).isFalse();
    }

    @Test
    void splitsThePaymentIntoRefundAndFee() {
        RefundQuote q = quote(Duration.ofDays(5)); // 70% of 100,000
        assertThat(q.refundAmount()).isEqualTo(70_000);
        assertThat(q.feeAmount()).isEqualTo(30_000);
        assertThat(q.refundAmount() + q.feeAmount()).isEqualTo(100_000);
    }

    @Test
    void roundsTheRefundDownSoTheFeeNeverGoesNegative() {
        RefundQuote q = RefundPolicy.quote(orderStartingIn(Duration.ofDays(5), 12_345, 0), NOW);
        assertThat(q.refundAmount()).isEqualTo(8_641); // floor(12,345 * 0.7)
        assertThat(q.feeAmount()).isEqualTo(3_704);
    }

    @Test
    void pointsComeBackInTheSameProportion() {
        RefundQuote q = RefundPolicy.quote(orderStartingIn(Duration.ofDays(5), 90_000, 10_000), NOW);
        assertThat(q.pointsRestored()).isEqualTo(7_000);
    }
}
