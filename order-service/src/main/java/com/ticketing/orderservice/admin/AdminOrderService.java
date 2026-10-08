package com.ticketing.orderservice.admin;

import com.ticketing.orderservice.admin.dto.AdminOrderListResponse;
import com.ticketing.orderservice.admin.dto.AdminOrderResponse;
import com.ticketing.orderservice.admin.dto.OrderSummaryResponse;
import com.ticketing.orderservice.authclient.AuthServiceClient;
import com.ticketing.orderservice.authclient.dto.UserInternalResponse;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.payment.Payment;
import com.ticketing.orderservice.payment.PaymentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminOrderService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final EventServiceClient eventServiceClient;
    private final AuthServiceClient authServiceClient;

    public AdminOrderService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            EventServiceClient eventServiceClient,
            AuthServiceClient authServiceClient) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.eventServiceClient = eventServiceClient;
        this.authServiceClient = authServiceClient;
    }

    /** Joins each order with its seat/event (event-service), payment and buyer (auth-service) for the admin list. */
    public AdminOrderListResponse listOrders(OrderStatus status, Pageable pageable) {
        Page<Orders> page = orderRepository.findAllByOptionalStatus(status, pageable);
        List<Orders> orders = page.getContent();

        List<Long> seatIds = orders.stream().map(Orders::getSeatId).distinct().toList();
        Map<Long, SeatDetailResponse> seatsById = seatIds.isEmpty()
                ? Map.of()
                : eventServiceClient.getSeats(seatIds).stream()
                        .collect(Collectors.toMap(SeatDetailResponse::seatId, Function.identity()));

        List<Long> orderIds = orders.stream().map(Orders::getId).toList();
        Map<Long, Payment> paymentsByOrderId = orderIds.isEmpty()
                ? Map.of()
                : paymentRepository.findByOrderIdIn(orderIds).stream()
                        .collect(Collectors.toMap(p -> p.getOrder().getId(), Function.identity()));

        // A page holds at most one page-size of buyers; a failed lookup just leaves that buyer unnamed.
        Map<Long, UserInternalResponse> buyers = new HashMap<>();
        for (Long userId : orders.stream().map(Orders::getUserId).distinct().toList()) {
            try {
                buyers.put(userId, authServiceClient.getUser(userId));
            } catch (RuntimeException e) {
                // leave unresolved
            }
        }

        List<AdminOrderResponse> content = orders.stream()
                .map(order -> {
                    UserInternalResponse buyer = buyers.get(order.getUserId());
                    return AdminOrderResponse.from(
                            order,
                            seatsById.get(order.getSeatId()),
                            paymentsByOrderId.get(order.getId()),
                            buyer == null ? null : buyer.name(),
                            buyer == null ? null : buyer.email());
                })
                .toList();
        return new AdminOrderListResponse(content, page.getTotalElements());
    }

    public List<OrderSummaryResponse> summaryByEvent() {
        // eventId -> [paidCount, cancelledCount, paidRevenue]
        Map<Long, long[]> byEvent = new LinkedHashMap<>();
        for (Object[] row : orderRepository.aggregateByEventAndStatus()) {
            Long eventId = (Long) row[0];
            OrderStatus status = (OrderStatus) row[1];
            long count = (Long) row[2];
            long total = row[3] == null ? 0 : ((Number) row[3]).longValue();
            long refunded = row[4] == null ? 0 : ((Number) row[4]).longValue();
            long[] acc = byEvent.computeIfAbsent(eventId, k -> new long[3]);
            if (status == OrderStatus.PAID) {
                acc[0] += count;
                acc[2] += total;
            } else if (status == OrderStatus.CANCELLED) {
                acc[1] += count;
            } else if (status == OrderStatus.PARTIALLY_REFUNDED) {
                // Cancelled, but the cancellation fee stays as revenue.
                acc[1] += count;
                acc[2] += total - refunded;
            }
        }
        List<OrderSummaryResponse> result = new ArrayList<>();
        byEvent.forEach((eventId, acc) -> result.add(new OrderSummaryResponse(eventId, acc[0], acc[1], acc[2])));
        return result;
    }
}
