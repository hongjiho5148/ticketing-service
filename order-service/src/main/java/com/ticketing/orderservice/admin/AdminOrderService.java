package com.ticketing.orderservice.admin;

import com.ticketing.orderservice.admin.dto.OrderSummaryResponse;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminOrderService {

    private final OrderRepository orderRepository;

    public AdminOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<OrderSummaryResponse> summaryByEvent() {
        // eventId -> [paidCount, cancelledCount, paidRevenue]
        Map<Long, long[]> byEvent = new LinkedHashMap<>();
        for (Object[] row : orderRepository.aggregateByEventAndStatus()) {
            Long eventId = (Long) row[0];
            OrderStatus status = (OrderStatus) row[1];
            long count = (Long) row[2];
            long total = row[3] == null ? 0 : ((Number) row[3]).longValue();
            long[] acc = byEvent.computeIfAbsent(eventId, k -> new long[3]);
            if (status == OrderStatus.PAID) {
                acc[0] += count;
                acc[2] += total;
            } else if (status == OrderStatus.CANCELLED) {
                acc[1] += count;
            }
        }
        List<OrderSummaryResponse> result = new ArrayList<>();
        byEvent.forEach((eventId, acc) -> result.add(new OrderSummaryResponse(eventId, acc[0], acc[1], acc[2])));
        return result;
    }
}
