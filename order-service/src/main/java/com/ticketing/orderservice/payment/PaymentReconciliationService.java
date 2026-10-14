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
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles the case pay() never got the chance to: the customer paid at PortOne, but the browser closed or the
 * server calls kept failing, so the order is still PENDING. Looks at one such order whose seat hold is no longer
 * alive and asks PortOne about every payment id issued for it:
 * <ul>
 *   <li>the reservation was confirmed after all (a commit failed after the seat was sold) -> finish the order;</li>
 *   <li>otherwise the seat is lost -> refund in full and fail the order.</li>
 * </ul>
 * It does nothing while the hold is alive (the customer can still finish through pay()) and nothing at all when
 * reservation-service cannot be reached - refunding a customer whose hold is in fact alive would be worse than waiting.
 */
@Service
@Transactional
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ReservationServiceClient reservationServiceClient;
    private final PortOneClient portOneClient;
    private final OrderService orderService;

    public PaymentReconciliationService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PaymentAttemptRepository attemptRepository,
            ReservationServiceClient reservationServiceClient,
            PortOneClient portOneClient,
            OrderService orderService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.reservationServiceClient = reservationServiceClient;
        this.portOneClient = portOneClient;
        this.orderService = orderService;
    }

    public void reconcile(Long orderId) {
        Orders order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING) {
            return;
        }
        List<PaymentAttempt> attempts = attemptRepository.findByOrderIdOrderByIdDesc(orderId);
        if (attempts.isEmpty()) {
            return; // the payment window was never opened for this order
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
                continue; // PortOne has no such payment: the window was opened but never completed
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
            // Left PENDING so the next tick tries again; if it keeps failing it needs a manual refund in the console.
            log.error("AUTO-REFUND FAILED for PortOne payment {} (order {}) - refund it manually: {}",
                    attempt.getPaymentId(), order.getId(), e.getMessage());
            return;
        }
        int paid = payment.amount() == null ? order.getTotalPrice() : (int) payment.amount().total();
        Payment record = new Payment(order, payment.methodLabel(), attempt.getPaymentId(), PaymentStatus.FAILED, paid, null);
        record.markRefunded(paid);
        paymentRepository.save(record);
        order.markFailed();
        try {
            reservationServiceClient.cancel(order.getReservationId());
        } catch (RuntimeException e) {
            // best effort: the expiry sweepers free the seat anyway
        }
    }
}
