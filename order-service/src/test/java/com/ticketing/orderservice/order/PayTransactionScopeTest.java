package com.ticketing.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.order.dto.PaymentResponse;
import com.ticketing.orderservice.payment.PaymentAttemptRepository;
import com.ticketing.orderservice.payment.PaymentRepository;
import com.ticketing.orderservice.payment.PaymentStatus;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.payment.portone.PortOnePaymentResponse;
import com.ticketing.orderservice.point.PointAccountRepository;
import com.ticketing.orderservice.point.PointTransactionRepository;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
import com.ticketing.orderservice.verification.IdentityVerificationService;
import com.ticketing.orderservice.verification.VerificationRecordRepository;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * pay()를 짧은 트랜잭션으로 나눈 목적을 증명한다. PortOne과 reservation-service를 기다리는 동안에는 DB 트랜잭션
 * (그래서 풀의 커넥션도)이 열려 있지 않다. 이 클래스는 일부러 @Transactional로 하지 않았다. 테스트가 관리하는
 * 트랜잭션은 모든 것을 감싸서 열려 있게 되므로, 실제 행을 커밋하고 끝나면 지운다.
 * compose MySQL을 쓰고, compose 네트워크 안에서 DB_HOST=mysql로 실행한다.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
class PayTransactionScopeTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());
    private static final int PRICE = 10_000;

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentAttemptRepository attemptRepository;
    @Autowired private VerificationRecordRepository verificationRepository;
    @Autowired private PointTransactionRepository pointTransactionRepository;
    @Autowired private PointAccountRepository pointAccountRepository;
    @Autowired private IdentityVerificationService verification;

    @MockitoBean private PortOneClient portOneClient;
    @MockitoBean private ReservationServiceClient reservationServiceClient;

    private Long userId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        userId = 9_100_000_000L + SEQ.incrementAndGet() % 1_000_000;
        long id = SEQ.incrementAndGet();
        orderId = orderRepository.save(new Orders(userId, id, id, LocalDateTime.now().plusDays(3), 1L, PRICE)).getId();
        verification.verifyForOrder(userId, orderId, new VerifyIdentityRequest(true));
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.findByOrderId(orderId).ifPresent(paymentRepository::delete);
        attemptRepository.deleteAll(attemptRepository.findByOrderIdOrderByIdDesc(orderId));
        verificationRepository.deleteAll(verificationRepository.findAll().stream()
                .filter(r -> orderId.equals(r.getOrderId())).toList());
        pointTransactionRepository.deleteAll(pointTransactionRepository.findAll().stream()
                .filter(t -> userId.equals(t.getUserId())).toList());
        pointAccountRepository.findById(userId).ifPresent(pointAccountRepository::delete);
        orderRepository.deleteById(orderId);
    }

    @Test
    void noTransactionIsOpenWhileWaitingOnPortOneOrOnReservationService() {
        String paymentId = orderService.createPaymentIntent(userId, orderId).paymentId();
        AtomicBoolean txDuringPortOne = new AtomicBoolean(true);
        AtomicBoolean txDuringConfirm = new AtomicBoolean(true);
        when(portOneClient.getPayment(anyString())).thenAnswer(inv -> {
            txDuringPortOne.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new PortOnePaymentResponse("PAID", new PortOnePaymentResponse.Amount(PRICE),
                    new PortOnePaymentResponse.Method("PaymentMethodCard", null));
        });
        when(reservationServiceClient.confirm(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            txDuringConfirm.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        });

        PaymentResponse response = orderService.pay(userId, orderId, new PaymentRequest(paymentId));

        assertThat(txDuringPortOne).isFalse();
        assertThat(txDuringConfirm).isFalse();
        // 그리고 각 단계가 실제로 따로 커밋됐다
        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.findByOrderId(orderId)).isPresent();
    }
}
