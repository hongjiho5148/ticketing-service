package com.ticketing.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.order.dto.PaymentResponse;
import com.ticketing.orderservice.payment.Payment;
import com.ticketing.orderservice.payment.PaymentRepository;
import com.ticketing.orderservice.payment.PaymentStatus;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.payment.portone.PortOnePaymentResponse;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
import com.ticketing.orderservice.verification.IdentityVerificationService;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객이 결제하는 동안 좌석 홀드가 끝났을 때 pay()가 하는 일이다. PortOne과 reservation-service는
 * mock이고, 주문·결제·혜택은 실제다(compose MySQL, 롤백). 다른 order-service 테스트처럼
 * compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
@Transactional
class PaymentHoldGuardTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());
    private static final int PRICE = 10_000;

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private IdentityVerificationService verification;
    @Autowired private PaymentRepository paymentRepository;

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
        when(portOneClient.getPayment(paymentId)).thenReturn(new PortOnePaymentResponse(
                "PAID", new PortOnePaymentResponse.Amount(PRICE), new PortOnePaymentResponse.Method("PaymentMethodCard", null)));
    }

    @Test
    void confirmedInTimeBecomesPaidWithoutAnyRefund() {
        PaymentResponse response = orderService.pay(userId, order.getId(), new PaymentRequest(paymentId));

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.failureReason()).isNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void whenTheHoldRanOutThePaymentIsRefundedAndTheOrderFails() {
        when(reservationServiceClient.confirm(order.getReservationId()))
                .thenThrow(new ApiException(ErrorCode.HOLD_EXPIRED));

        PaymentResponse response = orderService.pay(userId, order.getId(), new PaymentRequest(paymentId));

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failureReason()).isEqualTo("HOLD_EXPIRED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(portOneClient).cancelPayment(eq(paymentId), anyString());
        verify(reservationServiceClient).cancel(order.getReservationId());
        // 주문 목록은 이 값으로 "결제했다가 환불됨"과 "결제가 아예 안 됨"을 구분한다.
        assertThat(paymentOf(order).getRefundedAmount()).isEqualTo(PRICE);
    }

    @Test
    void aFailedAutoRefundIsNotRecordedAsRefunded() {
        when(reservationServiceClient.confirm(order.getReservationId()))
                .thenThrow(new ApiException(ErrorCode.HOLD_EXPIRED));
        doThrow(new RestClientException("PortOne down")).when(portOneClient).cancelPayment(eq(paymentId), anyString());

        PaymentResponse response = orderService.pay(userId, order.getId(), new PaymentRequest(paymentId));

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentOf(order).getRefundedAmount()).isZero();
    }

    @Test
    void aPaymentPortOneDoesNotReportPaidIsFailedWithoutARefund() {
        when(portOneClient.getPayment(paymentId)).thenReturn(new PortOnePaymentResponse(
                "FAILED", new PortOnePaymentResponse.Amount(PRICE), new PortOnePaymentResponse.Method("PaymentMethodCard", null)));

        PaymentResponse response = orderService.pay(userId, order.getId(), new PaymentRequest(paymentId));

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentOf(order).getRefundedAmount()).isZero();
        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
    }

    private Payment paymentOf(Orders order) {
        return paymentRepository.findByOrderIdIn(List.of(order.getId())).get(0);
    }

    @Test
    void aTransientReservationServiceFailureNeitherRefundsNorFailsTheOrder() {
        when(reservationServiceClient.confirm(order.getReservationId()))
                .thenThrow(new ApiException(ErrorCode.RESERVATION_SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> orderService.pay(userId, order.getId(), new PaymentRequest(paymentId)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_SERVICE_UNAVAILABLE));

        // 아직 PENDING이라 홀드가 유지되는 동안 같은 결제 id로 다시 시도할 수 있다
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
        verify(reservationServiceClient, never()).cancel(anyLong());
    }
}
