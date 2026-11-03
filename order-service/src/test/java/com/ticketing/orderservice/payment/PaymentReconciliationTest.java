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
 * 고객이 PortOne에서 결제했지만 pay()가 끝내 마무리하지 못한 주문이다. PortOne과 reservation-service는
 * mock이고 나머지는 실제다(compose MySQL, 롤백). 크론은 꺼 두고 조정 작업을 손으로 돌린다.
 * compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
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
        reservationIs("HOLDING", LocalDateTime.now().minusSeconds(30)); // 스위퍼는 아직 돌지 않았다
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
