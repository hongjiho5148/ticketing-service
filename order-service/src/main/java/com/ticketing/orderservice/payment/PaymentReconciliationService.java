package com.ticketing.orderservice.payment;

import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderService;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.payment.portone.PortOnePaymentResponse;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
import com.ticketing.orderservice.reservationclient.dto.ReservationDetailResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * pay()가 처리할 기회를 얻지 못한 경우를 정리한다. 고객은 PortOne에서 결제했지만 브라우저가 닫혔거나
 * 서버 호출이 계속 실패해서 주문이 아직 PENDING이다. 좌석 홀드가 더는 살아 있지 않은 그런 주문을 하나
 * 보고, 그 주문에 발급된 모든 결제 id를 PortOne에 묻는다.
 * <ul>
 *   <li>예약이 사실은 확정됐다면(좌석이 팔린 뒤 커밋이 실패했다) -> 주문을 마무리한다.</li>
 *   <li>그렇지 않으면 좌석을 잃은 것이다 -> 전액 환불하고 주문을 실패 처리한다.</li>
 * </ul>
 * 홀드가 살아 있는 동안은 아무것도 하지 않고(고객이 pay()로 마무리할 수 있다) reservation-service에 닿지
 * 못할 때도 아무것도 하지 않는다. 홀드가 실제로는 살아 있는 고객에게 환불하는 것이 기다리는 것보다 나쁘다.
 */
@Service
@Transactional(propagation = Propagation.SUPPORTS)
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ReservationServiceClient reservationServiceClient;
    private final PortOneClient portOneClient;
    private final OrderService orderService;
    private final TransactionTemplate transactionTemplate;

    public PaymentReconciliationService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentAttemptRepository attemptRepository,
            ReservationServiceClient reservationServiceClient,
            PortOneClient portOneClient,
            OrderService orderService,
            PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.reservationServiceClient = reservationServiceClient;
        this.portOneClient = portOneClient;
        this.orderService = orderService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void reconcile(Long orderId) {
        Orders order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING) {
            return;
        }
        List<PaymentAttempt> attempts = attemptRepository.findByOrderIdOrderByIdDesc(orderId);
        if (attempts.isEmpty()) {
            return; // 이 주문에 대해서는 결제창이 한 번도 열리지 않았다
        }

        ReservationDetailResponse reservation;
        try {
            reservation = reservationServiceClient.getReservation(order.getReservationId());
        } catch (RuntimeException e) {
            log.warn("Reconcile order {}: cannot tell whether the hold is alive, skipping ({})", orderId, e.getMessage());
            return;
        }
        boolean holdAlive = "HOLDING".equals(reservation.status())
                && reservation.holdExpireAt() != null
                && reservation.holdExpireAt().isAfter(LocalDateTime.now());
        if (holdAlive) {
            return;
        }
        boolean seatSold = "CONFIRMED".equals(reservation.status());

        for (PaymentAttempt attempt : attempts) {
            PortOnePaymentResponse payment;
            try {
                payment = portOneClient.getPayment(attempt.getPaymentId());
            } catch (RuntimeException e) {
                continue; // PortOne에 그런 결제가 없다: 창은 열었지만 끝내 완료하지 않았다
            }
            if (payment == null || !payment.isPaid()) {
                continue;
            }
            if (seatSold) {
                finishOrder(order, attempt);
            } else {
                refundAndFail(order, attempt, payment);
            }
            return;
        }
    }

    private void finishOrder(Orders order, PaymentAttempt attempt) {
        log.warn("Reconcile order {}: paid at PortOne and the seat is sold - completing the order", order.getId());
        try {
            orderService.pay(order.getUserId(), order.getId(), new PaymentRequest(attempt.getPaymentId()));
        } catch (RuntimeException e) {
            log.error("Reconcile order {}: could not complete the paid order, needs a look: {}", order.getId(), e.getMessage());
        }
    }

    private void refundAndFail(Orders order, PaymentAttempt attempt, PortOnePaymentResponse payment) {
        log.warn("Reconcile order {}: paid at PortOne ({}) but the seat hold is gone - refunding", order.getId(), attempt.getPaymentId());
        try {
            portOneClient.cancelPayment(attempt.getPaymentId(), "결제 후 좌석 홀드 만료로 자동 환불");
        } catch (RuntimeException e) {
            // PENDING으로 두어서 다음 주기에 다시 시도한다. 계속 실패하면 콘솔에서 수동으로 환불해야 한다.
            log.error("AUTO-REFUND FAILED for PortOne payment {} (order {}) - refund it manually: {}",
                    attempt.getPaymentId(), order.getId(), e.getMessage());
            return;
        }
        int paid = payment.amount() == null ? order.getTotalPrice() : (int) payment.amount().total();
        transactionTemplate.executeWithoutResult(status -> {
            Orders current = orderRepository.findById(order.getId()).orElseThrow();
            Payment record = new Payment(current, payment.methodLabel(), attempt.getPaymentId(), PaymentStatus.FAILED, paid, null);
            record.markRefunded(paid);
            paymentRepository.save(record);
            current.markFailed();
        });
        try {
            reservationServiceClient.cancel(order.getReservationId());
        } catch (RuntimeException e) {
            // 최선 노력: 만료 스위퍼가 어차피 좌석을 풀어 준다
        }
    }
}
