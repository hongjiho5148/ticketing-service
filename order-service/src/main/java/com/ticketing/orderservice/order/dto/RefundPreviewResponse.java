package com.ticketing.orderservice.order.dto;

import com.ticketing.orderservice.order.RefundQuote;

public record RefundPreviewResponse(
        boolean cancellable, int refundPercent, int refundAmount, int feeAmount, int pointsRestored) {

    public static RefundPreviewResponse from(RefundQuote quote) {
        return new RefundPreviewResponse(
                quote.isCancellable(),
                quote.refundPercent(),
                quote.refundAmount(),
                quote.feeAmount(),
                quote.pointsRestored());
    }
}
