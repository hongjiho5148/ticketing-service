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
 * 최근 미결제 주문을 도는 크론 스윕이다(다른 스위퍼와 같은 구조). 한 주문이 실패해도 나머지는 멈추지 않는다.
 * 조회 범위보다 오래된 주문은 영영 건드리지 않는다. 그때쯤이면 홀드는 오래전에 끝났고, 그렇지 않으면
 * 오래전에 버려진 모든 주문에 대해 PortOne에 계속 물어보게 된다.
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
