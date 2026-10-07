package com.ticketing.orderservice.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderService;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer-verification gate against the compose MySQL (rolled back). PortOne is mocked so the test
 * can prove pay() refuses an unverified order *before* it ever asks PortOne about the payment.
 * Run inside the compose network with DB_HOST=mysql, like the other order-service tests.
 */
@SpringBootTest(properties = {"ticket.issuance-sweep-cron=-", "reminder.sweep-cron=-"})
@Transactional
class IdentityVerificationServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired private IdentityVerificationService verification;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private VerificationRecordRepository recordRepository;

    @MockitoBean private PortOneClient portOneClient;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = 9_000_000_000L + SEQ.incrementAndGet() % 1_000_000;
    }

    private Orders pendingOrder() {
        long id = SEQ.incrementAndGet();
        return orderRepository.save(new Orders(userId, id, id, LocalDateTime.now().plusDays(3), 1L, 10000));
    }

    private void expectError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void attestationIsRecordedOnceAndRepeatsAreHarmless() {
        Orders order = pendingOrder();
        assertThat(verification.isSatisfied(order.getId())).isFalse();

        verification.verifyForOrder(userId, order.getId(), new VerifyIdentityRequest(true));
        verification.verifyForOrder(userId, order.getId(), new VerifyIdentityRequest(true));

        assertThat(verification.isSatisfied(order.getId())).isTrue();
        assertThat(recordRepository.findAll().stream().filter(r -> r.getOrderId().equals(order.getId()))).hasSize(1);
        VerificationRecord record = recordRepository.findAll().stream()
                .filter(r -> r.getOrderId().equals(order.getId())).findFirst().orElseThrow();
        assertThat(record.getMethod()).isEqualTo("SELF_ATTESTED");
        assertThat(record.getUserId()).isEqualTo(userId);
        assertThat(record.getStatementVersion()).isEqualTo("v1");
    }

    @Test
    void withoutAgreementNothingIsRecorded() {
        Orders order = pendingOrder();
        expectError(() -> verification.verifyForOrder(userId, order.getId(), new VerifyIdentityRequest(false)),
                ErrorCode.IDENTITY_NOT_CONFIRMED);
        expectError(() -> verification.verifyForOrder(userId, order.getId(), new VerifyIdentityRequest(null)),
                ErrorCode.IDENTITY_NOT_CONFIRMED);
        assertThat(verification.isSatisfied(order.getId())).isFalse();
    }

    @Test
    void onlyTheBuyerOfAPendingOrderCanVerify() {
        Orders order = pendingOrder();
        expectError(() -> verification.verifyForOrder(userId + 1, order.getId(), new VerifyIdentityRequest(true)),
                ErrorCode.FORBIDDEN);

        order.markPaid();
        expectError(() -> verification.verifyForOrder(userId, order.getId(), new VerifyIdentityRequest(true)),
                ErrorCode.ORDER_NOT_PENDING);
        expectError(() -> verification.verifyForOrder(userId, 0L, new VerifyIdentityRequest(true)),
                ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void payRefusesAnUnverifiedOrderBeforeAskingPortOne() {
        Orders order = pendingOrder();

        expectError(() -> orderService.pay(userId, order.getId(), new PaymentRequest("payment-1")),
                ErrorCode.IDENTITY_VERIFICATION_REQUIRED);

        verify(portOneClient, never()).getPayment(anyString());
    }

    @Test
    void theGateCanBeSwitchedOffAndAnUnknownProviderFailsFast() {
        IdentityVerificationService off = new IdentityVerificationService(
                mock(OrderRepository.class), mock(VerificationRecordRepository.class),
                List.of(new SelfAttestedVerificationProvider()), "SELF_ATTESTED", false);
        assertThat(off.isSatisfied(123L)).isTrue();

        assertThatThrownBy(() -> new IdentityVerificationService(
                mock(OrderRepository.class), mock(VerificationRecordRepository.class),
                List.of(new SelfAttestedVerificationProvider()), "PASS", true))
                .isInstanceOf(IllegalStateException.class);
    }
}
