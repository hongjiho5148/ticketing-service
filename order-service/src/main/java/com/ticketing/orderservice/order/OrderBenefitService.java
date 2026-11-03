package com.ticketing.orderservice.order;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.coupon.Coupon;
import com.ticketing.orderservice.coupon.CouponService;
import com.ticketing.orderservice.point.PointService;
import com.ticketing.orderservice.point.PointTransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문의 쿠폰과 포인트 처리다. 일부러 두 단계로 나눴다.
 * apply*는 검증만 하고 주문 가격을 고쳐 쓴다(아무것도 소모하지 않는다. 결제 대기 주문은 버려질 수 있는데
 * 그때 소모해 두면 포인트와 쿠폰 사용이 묶여 버린다). redeem()은 카드 결제가 검증된 뒤에
 * 실제로 소모한다.
 */
// noRollbackFor ApiException. 이유는 PointService 참고(pay()가 redeem 실패를 같은 트랜잭션 안에서 복구한다).
@Service
@Transactional(noRollbackFor = ApiException.class)
public class OrderBenefitService {

    /** PG가 결제할 수 있는 최소 금액이다. 혜택을 써도 합계가 이 아래로 내려갈 수는 없다. */
    public static final int MIN_PAYABLE_AMOUNT = 100;

    private static final int EARN_RATE_PERCENT = 1;

    private final CouponService couponService;
    private final PointService pointService;

    public OrderBenefitService(CouponService couponService, PointService pointService) {
        this.couponService = couponService;
        this.pointService = pointService;
    }

    public void applyCoupon(Orders order, String code) {
        Coupon coupon = couponService.findUsable(code, order.getUserId());
        int original = order.originalPrice();
        int discount = Math.min(coupon.discountFor(original), original - MIN_PAYABLE_AMOUNT);
        if (discount <= 0) {
            throw new ApiException(ErrorCode.PAYMENT_AMOUNT_TOO_LOW);
        }
        // 더 큰 쿠폰은 이미 적용된 포인트보다 포인트를 쓸 여유를 적게 남길 수 있다. 그만큼 줄여서 맞춘다.
        int points = Math.min(order.getPointsUsed(), original - discount - MIN_PAYABLE_AMOUNT);
        order.applyBenefits(coupon.getCode(), discount, points);
    }

    public void removeCoupon(Orders order) {
        order.applyBenefits(null, 0, order.getPointsUsed());
    }

    /** 이 주문에 쓰는 포인트를 설정한다(더하지 않고 교체한다). 0이면 지운다. */
    public void applyPoints(Orders order, int points) {
        int max = order.originalPrice() - order.getDiscountAmount() - MIN_PAYABLE_AMOUNT;
        if (points < 0 || points > max) {
            throw new ApiException(ErrorCode.POINTS_INVALID);
        }
        if (points > pointService.balance(order.getUserId())) {
            throw new ApiException(ErrorCode.POINTS_INSUFFICIENT);
        }
        order.applyBenefits(order.getCouponCode(), order.getDiscountAmount(), points);
    }

    /**
     * 주문의 포인트와 쿠폰을 소모하고 구매 적립을 준다. 둘 중 하나라도 더는 지킬 수 없으면(잔액을 다른 곳에
     * 썼거나 그 사이 쿠폰이 다 소진됐으면) ApiException을 던진다. 그 경우 아무것도 소모된 채로 남지 않고,
     * 호출한 쪽이 카드 결제를 되돌려야 한다.
     */
    public void redeem(Orders order) {
        if (order.isBenefitsRedeemed()) {
            return; // 다시 시도한 결제: 이미 소모됐으므로 두 번 소모하지 않는다
        }
        Long userId = order.getUserId();
        int points = order.getPointsUsed();
        boolean pointsTaken = false;
        try {
            if (points > 0) {
                pointService.take(userId, points);
                pointsTaken = true;
            }
            if (order.getCouponCode() != null) {
                couponService.redeem(order);
            }
        } catch (ApiException e) {
            if (pointsTaken) {
                pointService.give(userId, points);
            }
            throw e;
        }

        if (points > 0) {
            pointService.record(userId, -points, PointTransactionType.USE, order.getId());
        }
        long reward = (long) order.getTotalPrice() * EARN_RATE_PERCENT / 100;
        if (reward > 0) {
            pointService.give(userId, reward);
            pointService.record(userId, reward, PointTransactionType.EARN, order.getId());
        }
        order.markBenefitsRedeemed();
    }

    /**
     * 취소된 주문의 포인트 쪽을 되돌린다. 받았던 적립은 전부 회수하고, 쓴 포인트는 현금 환불과 같은
     * 비율로 돌려준다(70% 환불이면 포인트의 70%). 그렇지 않으면 포인트로 결제하는 것이 취소 수수료를
     * 피하는 방법이 된다. 쿠폰은 전액 환불일 때만 돌아온다(RefundPolicy.restoresCoupon 참고). 부분 환불이면
     * 취소 수수료를 받았으므로 쿠폰은 쓴 것으로 남는다.
     */
    public void reverse(Orders order, int refundPercent) {
        Long userId = order.getUserId();
        long restored = (long) order.getPointsUsed() * refundPercent / 100;
        if (restored > 0) {
            pointService.give(userId, restored);
            pointService.record(userId, restored, PointTransactionType.RESTORE, order.getId());
        }
        pointService.clawbackEarned(userId, order.getId());
        if (RefundPolicy.restoresCoupon(refundPercent)) {
            couponService.restore(order);
        }
        order.clearBenefitsRedeemed();
    }
}
