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
 * Coupon + point handling for an order, in two phases on purpose:
 * apply* only validates and rewrites the order's price (nothing is consumed - a pending order can be
 * abandoned, and consuming then would strand points/coupon uses), and redeem() actually consumes them
 * once the card payment has been verified.
 */
// noRollbackFor ApiException - see PointService for why (pay() recovers from redeem failures in-transaction).
@Service
@Transactional(noRollbackFor = ApiException.class)
public class OrderBenefitService {

    /** Smallest amount the PG will charge - benefits can never push the total below this. */
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
        // A bigger coupon can leave less room for points than were already applied - trim them to fit.
        int points = Math.min(order.getPointsUsed(), original - discount - MIN_PAYABLE_AMOUNT);
        order.applyBenefits(coupon.getCode(), discount, points);
    }

    public void removeCoupon(Orders order) {
        order.applyBenefits(null, 0, order.getPointsUsed());
    }

    /** Sets (replaces, not adds to) the points used on this order; 0 clears them. */
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
     * Consumes the order's points + coupon and credits the purchase reward. Throws ApiException if
     * either can no longer be honored (balance spent elsewhere, coupon exhausted in the meantime) -
     * in which case nothing stays consumed and the caller has to unwind the card payment.
     */
    public void redeem(Orders order) {
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
    }

    /**
     * Undoes the point side of a cancelled order: the reward it earned is taken back in full, and
     * the points spent come back in the same proportion as the cash refund (a 70% refund returns
     * 70% of the points) - otherwise paying with points would be a way around the cancellation fee.
     * The coupon comes back only with a full refund (see RefundPolicy.restoresCoupon) - with a partial refund
     * a cancellation fee was kept and the coupon stays spent.
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
    }
}
