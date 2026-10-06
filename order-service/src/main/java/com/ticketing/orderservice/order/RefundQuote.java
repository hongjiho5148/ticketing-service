package com.ticketing.orderservice.order;

/**
 * @param refundPercent  share of the payment given back (0 = cancellation no longer allowed)
 * @param refundAmount   won returned to the card/easy-pay
 * @param feeAmount      won kept as the cancellation fee (paid - refundAmount)
 * @param pointsRestored points spent on the order that go back to the customer's balance
 */
public record RefundQuote(int refundPercent, int refundAmount, int feeAmount, int pointsRestored) {

    public boolean isCancellable() {
        return refundPercent > 0;
    }
}
