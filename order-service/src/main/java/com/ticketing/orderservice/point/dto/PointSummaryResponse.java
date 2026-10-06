package com.ticketing.orderservice.point.dto;

import com.ticketing.orderservice.point.PointTransaction;
import com.ticketing.orderservice.point.PointTransactionType;
import java.time.LocalDateTime;
import java.util.List;

public record PointSummaryResponse(long balance, List<Entry> transactions) {

    public record Entry(long delta, PointTransactionType type, Long orderId, LocalDateTime createdAt) {

        public static Entry from(PointTransaction tx) {
            return new Entry(tx.getDelta(), tx.getType(), tx.getOrderId(), tx.getCreatedAt());
        }
    }
}
