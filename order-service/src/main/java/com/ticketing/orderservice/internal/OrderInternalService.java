package com.ticketing.orderservice.internal;

import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderInternalService {

    private final OrderRepository orderRepository;

    public OrderInternalService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public boolean hasPaidOrder(Long userId, Long eventId) {
        return orderRepository.existsByUserIdAndEventIdAndStatus(userId, eventId, OrderStatus.PAID);
    }
}
