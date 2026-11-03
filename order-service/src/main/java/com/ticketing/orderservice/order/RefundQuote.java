package com.ticketing.orderservice.order;

/**
 * @param refundPercent  결제 금액 중 돌려주는 비율(0 = 더는 취소할 수 없음)
 * @param refundAmount   카드/간편결제로 돌려주는 원화 금액
 * @param feeAmount      취소 수수료로 남기는 원화 금액(결제액 - refundAmount)
 * @param pointsRestored 주문에 쓴 포인트 중 고객 잔액으로 돌아가는 포인트
 * @param couponRestored 주문의 쿠폰을 다시 쓸 수 있게 됨(전액 환불일 때만)
 * @param couponForfeited 주문에 쓴 쿠폰이 쓴 것으로 남음(부분 환불)
 */
public record RefundQuote(
        int refundPercent,
        int refundAmount,
        int feeAmount,
        int pointsRestored,
        boolean couponRestored,
        boolean couponForfeited) {

    public boolean isCancellable() {
        return refundPercent > 0;
    }
}
