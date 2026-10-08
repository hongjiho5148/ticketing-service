package com.ticketing.orderservice.order;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cancellation refund tiers by how long before the show the cancel happens. Tuning the policy means
 * editing TIERS - nothing else knows the numbers (the order page asks for a quote instead of hardcoding them).
 */
public final class RefundPolicy {

    /** Checked top to bottom: the first tier whose lead time the cancellation still meets wins. */
    private static final List<Tier> TIERS = List.of(
            new Tier(Duration.ofDays(7), 100),
            new Tier(Duration.ofDays(3), 70),
            new Tier(Duration.ofDays(1), 30));

    private RefundPolicy() {
    }

    /**
     * A coupon is given back only with a full refund - no fee was kept, so the customer ends up exactly where
     * they started. With a partial refund they paid a cancellation fee and the coupon stays spent, otherwise
     * a coupon would be a way to offset the fee. The quote and the actual reversal both ask here.
     */
    public static boolean restoresCoupon(int refundPercent) {
        return refundPercent == 100;
    }

    /** What cancelling {@code order} at {@code now} would give back. refundPercent 0 means "too late - no cancellation". */
    public static RefundQuote quote(Orders order, LocalDateTime now) {
        Duration untilShow = Duration.between(now, order.getEventStartAt());
        int percent = TIERS.stream()
                .filter(tier -> untilShow.compareTo(tier.minimumLead()) >= 0)
                .mapToInt(Tier::percent)
                .findFirst()
                .orElse(0);

        int paid = order.getTotalPrice();
        int refund = (int) ((long) paid * percent / 100);
        int pointsBack = (int) ((long) order.getPointsUsed() * percent / 100);
        boolean usedCoupon = order.getCouponCode() != null && percent > 0;
        return new RefundQuote(
                percent, refund, paid - refund, pointsBack, usedCoupon && restoresCoupon(percent), usedCoupon && !restoresCoupon(percent));
    }

    private record Tier(Duration minimumLead, int percent) {
    }
}
