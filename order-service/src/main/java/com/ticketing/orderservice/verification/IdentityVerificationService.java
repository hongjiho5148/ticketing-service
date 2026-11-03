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
 * 결제 전 구매자 확인이다. 결제 자체는 우리가 통제할 수 없는 PortOne에서 일어나므로, 주문 흐름은 결제창을
 * 열기 *전에* 이 기록을 요구하고, pay()는 기록이 없는 주문의 확정을 거절한다.
 */
@Service
@Transactional
public class IdentityVerificationService {

    // 결제 화면의 문구가 바뀌면 올린다.
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
        // 설정된 제공자가 없으면 첫 결제 때가 아니라 시작할 때 실패한다.
        this.provider = providers.stream()
                .filter(p -> p.method().equals(method))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No IdentityVerificationProvider for method " + method));
    }

    /** 멱등하다. 이미 확인된 주문을 다시 확인해도 아무 일도 일어나지 않으므로, 다시 시도한 결제가 또 호출해도 된다. */
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
        // flush해서 동시에 들어온 중복이 여기서 유니크 제약 위반으로 드러나게 한다(컨트롤러 참고).
        recordRepository.saveAndFlush(new VerificationRecord(orderId, userId, provider.method(), STATEMENT_VERSION));
    }

    @Transactional(readOnly = true)
    public boolean isSatisfied(Long orderId) {
        return !required || recordRepository.existsByOrderId(orderId);
    }
}
