package com.ticketing.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.order.dto.PaymentResponse;
import com.ticketing.orderservice.payment.PaymentAttemptRepository;
import com.ticketing.orderservice.payment.PaymentRepository;
import com.ticketing.orderservice.payment.PaymentStatus;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.payment.portone.PortOnePaymentResponse;
import com.ticketing.orderservice.point.PointAccountRepository;
import com.ticketing.orderservice.point.PointService;
import com.ticketing.orderservice.point.PointTransactionRepository;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
import com.ticketing.orderservice.verification.IdentityVerificationService;
import com.ticketing.orderservice.verification.VerificationRecordRepository;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * pay()는 네트워크 호출 사이마다 짧은 트랜잭션 여러 개로 돌기 때문에, 일부가 실패한 뒤의 재시도가 고객의 포인트를
 * 정확히 있어야 할 자리에 두어야 한다. 좌석 확정이 실패하면 돌려주고, 재시도가 성공하면 한 번만 소모한다.
 * 일부러 @Transactional로 하지 않았다. pay()의 모든 단계가 운영과 똑같이 실제로 커밋된다(테스트가 공유하는
 * 트랜잭션은 대량 포인트 UPDATE의 캐시 불일치 효과를 가려 버린다). 그래서 끝나면 행을 지운다. compose MySQL을
 * 쓰고 PortOne과 reservation-service는 mock이다.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
class PayRetryTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());
    private static final int PRICE = 10_000;
    private static final int POINTS_USED = 500;
    private static final int CHARGED = PRICE - POINTS_USED;
    private static final long REWARD = CHARGED / 100; // 구매 적립 1%

    @Autowired private OrderService orderService;
    @Autowired private OrderBenefitService benefitService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PointService pointService;
    @Autowired private IdentityVerificationService verification;

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentAttemptRepository attemptRepository;
    @Autowired private VerificationRecordRepository verificationRepository;
    @Autowired private PointTransactionRepository pointTransactionRepository;
    @Autowired private PointAccountRepository pointAccountRepository;

    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired private MeterRegistry meters;

    @MockitoBean private PortOneClient portOneClient;
    @MockitoBean private ReservationServiceClient reservationServiceClient;

    private Long userId;
    private Orders order;
    private String paymentId;

    @BeforeEach
    void setUp() {
        userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
        pointService.give(userId, 1_000);
        long id = SEQ.incrementAndGet();
        order = orderRepository.save(new Orders(userId, id, id, LocalDateTime.now().plusDays(3), 1L, PRICE));
        order.applyBenefits(null, 0, POINTS_USED);
        order = orderRepository.save(order);
        verification.verifyForOrder(userId, order.getId(), new VerifyIdentityRequest(true));
        paymentId = orderService.createPaymentIntent(userId, order.getId()).paymentId();
        when(portOneClient.getPayment(paymentId)).thenReturn(new PortOnePaymentResponse(
                "PAID", new PortOnePaymentResponse.Amount(CHARGED), new PortOnePaymentResponse.Method("PaymentMethodCard", null)));
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.findByOrderId(order.getId()).ifPresent(paymentRepository::delete);
        attemptRepository.deleteAll(attemptRepository.findByOrderIdOrderByIdDesc(order.getId()));
        verificationRepository.deleteAll(verificationRepository.findAll().stream()
                .filter(r -> order.getId().equals(r.getOrderId())).toList());
        pointTransactionRepository.deleteAll(pointTransactionRepository.findAll().stream()
                .filter(t -> userId.equals(t.getUserId())).toList());
        pointAccountRepository.findById(userId).ifPresent(pointAccountRepository::delete);
        orderRepository.deleteById(order.getId());
    }

    private long balance() {
        return pointService.balance(userId);
    }

    private Orders reload() {
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private void redeemInTransaction() {
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(
                status -> benefitService.redeem(orderRepository.findById(order.getId()).orElseThrow()));
    }

    @Test
    void redeemingTwiceConsumesTheBenefitsOnlyOnce() {
        // redeem()은 트랜잭션이 필요하다(pay() 안에서는 트랜잭션이다). pay()와 같은 방식으로 실행하고 표식을 저장한다
        redeemInTransaction();
        redeemInTransaction();

        assertThat(balance()).isEqualTo(1_000 - POINTS_USED + REWARD);
        assertThat(reload().isBenefitsRedeemed()).isTrue();
    }

    @Test
    void aFailedConfirmationGivesThePointsBackAndTheRetryConsumesThemOnce() {
        when(reservationServiceClient.confirm(anyLong()))
                .thenThrow(new ApiException(ErrorCode.RESERVATION_SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> orderService.pay(userId, order.getId(), new PaymentRequest(paymentId)))
                .isInstanceOf(ApiException.class);

        // 시도하기 전과 정확히 같다
        assertThat(balance()).isEqualTo(1_000);
        assertThat(reload().isBenefitsRedeemed()).isFalse();
        assertThat(reload().getStatus()).isEqualTo(OrderStatus.PENDING);

        // reservation-service가 돌아왔다. 같은 결제 id가 통과한다
        org.mockito.Mockito.reset(reservationServiceClient);
        double paidBefore = meters.counter("payments_total", "result", "success").count();
        PaymentResponse response = orderService.pay(userId, order.getId(), new PaymentRequest(paymentId));
        assertThat(meters.counter("payments_total", "result", "success").count()).isEqualTo(paidBefore + 1);

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(reload().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(balance()).isEqualTo(1_000 - POINTS_USED + REWARD);
    }

    @Test
    void anExpiredHoldGivesEverythingBack() {
        when(reservationServiceClient.confirm(anyLong())).thenThrow(new ApiException(ErrorCode.HOLD_EXPIRED));
        double failedBefore = meters.counter("payments_total", "result", "failed").count();

        PaymentResponse response = orderService.pay(userId, order.getId(), new PaymentRequest(paymentId));
        assertThat(meters.counter("payments_total", "result", "failed").count()).isEqualTo(failedBefore + 1);

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reload().getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(balance()).isEqualTo(1_000);
        assertThat(reload().isBenefitsRedeemed()).isFalse();
    }
}
