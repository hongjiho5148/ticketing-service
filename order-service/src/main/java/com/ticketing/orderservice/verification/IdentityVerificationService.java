package com.ticketing.orderservice.verification;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buyer verification before payment. The payment itself happens at PortOne, outside our control,
 * so the order flow demands the record *before* it opens the payment window, and pay() refuses to
 * confirm an order that has none.
 */
@Service
@Transactional
public class IdentityVerificationService {

    // Bump when the wording on the checkout screen changes.
    static final String STATEMENT_VERSION = "v1";

    private final OrderRepository orderRepository;
    private final VerificationRecordRepository recordRepository;
    private final IdentityVerificationProvider provider;
    private final boolean required;

    public IdentityVerificationService(
            OrderRepository orderRepository,
            VerificationRecordRepository recordRepository,
            List<IdentityVerificationProvider> providers,
            @Value("${identity-verification.provider}") String method,
            @Value("${identity-verification.required}") boolean required) {
        this.orderRepository = orderRepository;
        this.recordRepository = recordRepository;
        this.required = required;
        // Fail at startup, not at the first checkout, if the configured provider doesn't exist.
        this.provider = providers.stream()
                .filter(p -> p.method().equals(method))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No IdentityVerificationProvider for method " + method));
    }

    /** Idempotent: verifying an already-verified order is a no-op, so a retried payment can call it again. */
    public void verifyForOrder(Long userId, Long orderId, VerifyIdentityRequest request) {
        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_NOT_PENDING);
        }
        if (recordRepository.existsByOrderId(orderId)) {
            return;
        }
        provider.verify(userId, request);
        // Flushed so a concurrent duplicate surfaces here as a unique-constraint violation (see the controller).
        recordRepository.saveAndFlush(new VerificationRecord(orderId, userId, provider.method(), STATEMENT_VERSION));
    }

    @Transactional(readOnly = true)
    public boolean isSatisfied(Long orderId) {
        return !required || recordRepository.existsByOrderId(orderId);
    }
}
