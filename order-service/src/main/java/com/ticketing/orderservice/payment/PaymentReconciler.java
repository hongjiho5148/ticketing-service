package com.ticketing.orderservice.payment;

import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-sweep over recent unpaid orders (same shape as the other sweepers). One order failing never stops the rest.
 * Orders older than the look-back window are left alone for good: the hold is long gone by then and PortOne would be
 * asked about every old abandoned order forever.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final OrderRepository orderRepository;
    private final PaymentReconciliationService reconciliationService;
    private final long lookbackMinutes;

    public PaymentReconciler(
            OrderRepository orderRepository,
            PaymentReconciliationService reconciliationService,
            @Value("${payment.reconcile-lookback-minutes}") long lookbackMinutes) {
        this.orderRepository = orderRepository;
        this.reconciliationService = reconciliationService;
        this.lookbackMinutes = lookbackMinutes;
    }

    @Scheduled(cron = "${payment.reconcile-sweep-cron}")
    public void reconcileRecentUnpaidOrders() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(lookbackMinutes);
        for (Orders order : orderRepository.findByStatusAndCreatedAtAfter(OrderStatus.PENDING, since)) {
            try {
                reconciliationService.reconcile(order.getId());
            } catch (RuntimeException e) {
                log.warn("Payment reconcile failed for order {}: {}", order.getId(), e.getMessage());
            }
        }
    }
}
