package com.ticketing.orderservice.order;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 공연 시작 전 취소 시점에 따른 환불 구간이다. 정책을 바꾸려면 TIERS만 고치면 된다. 다른 곳은 숫자를
 * 알지 못한다(주문 화면은 숫자를 하드코딩하지 않고 견적을 서버에 묻는다).
 */
public final class RefundPolicy {

    /** 위에서 아래로 확인한다. 취소가 요구하는 남은 시간을 만족하는 첫 구간이 적용된다. */
    private static final List<Tier> TIERS = List.of(
            new Tier(Duration.ofDays(7), 100),
            new Tier(Duration.ofDays(3), 70),
            new Tier(Duration.ofDays(1), 30));

    private RefundPolicy() {
    }

    /**
     * 쿠폰은 전액 환불일 때만 돌려준다. 수수료를 받지 않았으니 고객은 시작할 때와 똑같은 상태가 된다.
     * 부분 환불이면 취소 수수료를 냈고 쿠폰은 쓴 것으로 남는다. 그렇지 않으면 쿠폰이 수수료를 상쇄하는 수단이
     * 된다. 견적과 실제 되돌림이 모두 여기를 확인한다.
     */
    public static boolean restoresCoupon(int refundPercent) {
        return refundPercent == 100;
    }

    /** {@code now}에 {@code order}를 취소하면 돌려받는 금액. refundPercent가 0이면 "너무 늦어서 취소할 수 없다"는 뜻이다. */
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
