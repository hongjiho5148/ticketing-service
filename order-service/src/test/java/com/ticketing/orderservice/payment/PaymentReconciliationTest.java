package com.ticketing.orderservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderService;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.payment.portone.PortOnePaymentResponse;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
import com.ticketing.orderservice.reservationclient.dto.ReservationDetailResponse;
import com.ticketing.orderservice.verification.IdentityVerificationService;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders the customer paid for at PortOne without pay() ever finishing them. PortOne and reservation-service are
 * mocked, the rest is real (compose MySQL, rolled back); the cron is off and the reconciler is driven by hand. Run
 * inside the compose network with DB_HOST=mysql.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
@Transactional
class PaymentReconciliationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());
    private static final int PRICE = 10_000;

    @Autowired private PaymentReconciliationService reconciliation;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private IdentityVerificationService verification;

    @MockitoBean private PortOneClient portOneClient;
    @MockitoBean private ReservationServiceClient reservationServiceClient;

    private Long userId;
    private Orders order;
    private String paymentId;

    @BeforeEach
    void setUp() {
        userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        long id = SEQ.incrementAndGet();
        order = orderRepository.save(new Orders(userId, id, id, LocalDateTime.now().plusDays(3), 1L, PRICE));
        verification.verifyForOrder(userId, order.getId(), new VerifyIdentityRequest(true));
        paymentId = orderService.createPaymentIntent(userId, order.getId()).paymentId();
    }

    private void reservationIs(String status, LocalDateTime holdExpireAt) {
        when(reservationServiceClient.getReservation(order.getReservationId()))
                .thenReturn(new ReservationDetailResponse(order.getReservationId(), userId, order.getSeatId(), status, holdExpireAt));
    }

    private void portOneSaysPaid() {
        when(portOneClient.getPayment(paymentId)).thenReturn(new PortOnePaymentResponse(
                "PAID", new PortOnePaymentResponse.Amount(PRICE), new PortOnePaymentResponse.Method("PaymentMethodCard", null)));
    }

    @Test
    void refundsAPaidPaymentWhoseSeatHoldIsGone() {
        reservationIs("EXPIRED", LocalDateTime.now().minusMinutes(10));
        portOneSaysPaid();

        reconciliation.reconcile(order.getId());

        verify(portOneClient).cancelPayment(eq(paymentId), anyString());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        Payment record = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(record.getRefundedAmount()).isEqualTo(PRICE);
        verify(reservationServiceClient).cancel(order.getReservationId());
    }

    @Test
    void aHoldStillMarkedHoldingButPastItsExpiryCountsAsGone() {
        reservationIs("HOLDING", LocalDateTime.now().minusSeconds(30)); // the sweeper has not run yet
        portOneSaysPaid();

        reconciliation.reconcile(order.getId());

        verify(portOneClient).cancelPayment(eq(paymentId), anyString());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void leavesTheOrderAloneWhileTheHoldIsAlive() {
        reservationIs("HOLDING", LocalDateTime.now().plusMinutes(2));
        portOneSaysPaid();

        reconciliation.reconcile(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(portOneClient, never()).getPayment(anyString());
        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void doesNothingWhenReservationServiceCannotBeReached() {
        when(reservationServiceClient.getReservation(order.getReservationId()))
                .thenThrow(new ApiException(ErrorCode.RESERVATION_SERVICE_UNAVAILABLE));
        portOneSaysPaid();

        reconciliation.reconcile(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void ignoresAnAttemptThatNeverGotPaid() {
        reservationIs("EXPIRED", LocalDateTime.now().minusMinutes(10));
        when(portOneClient.getPayment(paymentId)).thenThrow(new IllegalStateException("404 from PortOne"));

        reconciliation.reconcile(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void anOrderWhoseWindowWasNeverOpenedIsNotEvenLookedAt() {
        long id = SEQ.incrementAndGet();
        Orders untouched = orderRepository.save(new Orders(userId, id, id, LocalDateTime.now().plusDays(3), 1L, PRICE));

        reconciliation.reconcile(untouched.getId());

        verify(reservationServiceClient, never()).getReservation(untouched.getReservationId());
        verify(portOneClient, never()).getPayment(anyString());
    }

    @Test
    void completesTheOrderWhenTheSeatWasSoldAfterAll() {
        reservationIs("CONFIRMED", LocalDateTime.now().minusMinutes(1));
        portOneSaysPaid();

        reconciliation.reconcile(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void aFailedRefundLeavesTheOrderPendingForTheNextTick() {
        reservationIs("EXPIRED", LocalDateTime.now().minusMinutes(10));
        portOneSaysPaid();
        org.mockito.Mockito.doThrow(new IllegalStateException("PortOne down"))
                .when(portOneClient).cancelPayment(anyString(), anyString());

        reconciliation.reconcile(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(paymentRepository.findByOrderId(order.getId())).isEmpty();
        verify(reservationServiceClient, never()).cancel(anyLong());
    }
}
