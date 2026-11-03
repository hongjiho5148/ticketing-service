package com.ticketing.orderservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderService;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.order.dto.PaymentIntentResponse;
import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
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
 * pay()는 서버가 바로 그 주문을 위해 발급한 결제 id만 받아들인다. compose MySQL에서 실행한다(롤백).
 * PortOne과 reservation-service는 mock이다. compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
@Transactional
class PaymentAttemptTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());
    private static final int PRICE = 50_000;

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentAttemptRepository attemptRepository;
    @Autowired private IdentityVerificationService verification;

    @MockitoBean private PortOneClient portOneClient;
    @MockitoBean private ReservationServiceClient reservationServiceClient;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    }

    private Orders verifiedOrder() {
        long id = SEQ.incrementAndGet();
        Orders order = orderRepository.save(new Orders(userId, id, id, LocalDateTime.now().plusDays(3), 1L, PRICE));
        verification.verifyForOrder(userId, order.getId(), new VerifyIdentityRequest(true));
        return order;
    }

    @Test
    void anIntentIsBoundToTheOrderAndCarriesTheServersAmount() {
        Orders order = verifiedOrder();

        PaymentIntentResponse intent = orderService.createPaymentIntent(userId, order.getId());

        assertThat(intent.amount()).isEqualTo(PRICE);
        PaymentAttempt attempt = attemptRepository.findByPaymentId(intent.paymentId()).orElseThrow();
        assertThat(attempt.getOrderId()).isEqualTo(order.getId());
    }

    @Test
    void everyIntentGetsItsOwnPaymentId() {
        Orders order = verifiedOrder();

        String first = orderService.createPaymentIntent(userId, order.getId()).paymentId();
        String second = orderService.createPaymentIntent(userId, order.getId()).paymentId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void payRejectsAPaymentIdTheServerNeverIssued() {
        Orders order = verifiedOrder();

        assertThatThrownBy(() -> orderService.pay(userId, order.getId(), new PaymentRequest("made-up-id")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_REGISTERED));

        verify(portOneClient, never()).getPayment(anyString());
    }

    @Test
    void onePaidPaymentCannotBePresentedForAnotherOrderOfTheSamePrice() {
        Orders paidFor = verifiedOrder();
        Orders other = verifiedOrder(); // 같은 가격, 같은 구매자
        String paymentOfFirst = orderService.createPaymentIntent(userId, paidFor.getId()).paymentId();

        assertThatThrownBy(() -> orderService.pay(userId, other.getId(), new PaymentRequest(paymentOfFirst)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_REGISTERED));

        verify(portOneClient, never()).getPayment(anyString());
    }

    @Test
    void anIntentForSomeoneElsesOrderIsForbidden() {
        Orders order = verifiedOrder();

        assertThatThrownBy(() -> orderService.createPaymentIntent(userId + 1, order.getId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
