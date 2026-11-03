package com.ticketing.orderservice.order;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.dto.CheckoutResponse;
import com.ticketing.orderservice.order.dto.OrderCreateRequest;
import com.ticketing.orderservice.order.dto.OrderHistoryListResponse;
import com.ticketing.orderservice.order.dto.OrderHistoryResponse;
import com.ticketing.orderservice.order.dto.OrderResponse;
import com.ticketing.orderservice.order.dto.PaymentIntentResponse;
import com.ticketing.orderservice.payment.PaymentAttempt;
import com.ticketing.orderservice.payment.PaymentAttemptRepository;
import java.util.UUID;
import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.order.dto.PaymentResponse;
import com.ticketing.orderservice.payment.Payment;
import com.ticketing.orderservice.payment.PaymentRepository;
import com.ticketing.orderservice.payment.PaymentStatus;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.payment.portone.PortOnePaymentResponse;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
import com.ticketing.orderservice.reservationclient.dto.ReservationDetailResponse;
import com.ticketing.orderservice.transfer.TicketTransferRepository;
import com.ticketing.orderservice.transfer.TransferPolicy;
import com.ticketing.orderservice.verification.IdentityVerificationService;
import java.util.Set;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

@Service
@Transactional
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String HOLDING_STATUS = "HOLDING";

    // reservation-service는 좌석을 5분 동안 잡아 둔다. 여분의 1분은 시계 차이를 위한 여유다.
    private static final Duration MAX_HOLD_AGE = Duration.ofMinutes(6);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final EventServiceClient eventServiceClient;
    private final ReservationServiceClient reservationServiceClient;
    private final OrderBenefitService orderBenefitService;
    private final TicketTransferRepository transferRepository;
    private final TransferPolicy transferPolicy;
    private final IdentityVerificationService identityVerificationService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public OrderService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PortOneClient portOneClient,
            EventServiceClient eventServiceClient,
            ReservationServiceClient reservationServiceClient,
            OrderBenefitService orderBenefitService,
            TicketTransferRepository transferRepository,
            TransferPolicy transferPolicy,
            IdentityVerificationService identityVerificationService,
            PaymentAttemptRepository paymentAttemptRepository,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.portOneClient = portOneClient;
        this.eventServiceClient = eventServiceClient;
        this.reservationServiceClient = reservationServiceClient;
        this.orderBenefitService = orderBenefitService;
        this.transferRepository = transferRepository;
        this.transferPolicy = transferPolicy;
        this.identityVerificationService = identityVerificationService;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OrderResponse applyCoupon(Long userId, Long orderId, String code) {
        Orders order = loadPendingOrder(userId, orderId);
        orderBenefitService.applyCoupon(order, code);
        return OrderResponse.from(order);
    }

    public OrderResponse removeCoupon(Long userId, Long orderId) {
        Orders order = loadPendingOrder(userId, orderId);
        orderBenefitService.removeCoupon(order);
        return OrderResponse.from(order);
    }

    public OrderResponse applyPoints(Long userId, Long orderId, int points) {
        Orders order = loadPendingOrder(userId, orderId);
        orderBenefitService.applyPoints(order, points);
        return OrderResponse.from(order);
    }

    private Orders loadPendingOrder(Long userId, Long orderId) {
        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_NOT_PENDING);
        }
        return order;
    }

    /**
     * 이 주문에 쓸 새 결제 id를 등록한다. pay()는 여기서 등록한 id만 받아들이므로 한 주문을 위해 한 결제를
     * 다른 주문에 내밀 수 없고, 조정 작업은 어떤 PortOne 결제를 조회해야 하는지 알 수 있다.
     */
    public PaymentIntentResponse createPaymentIntent(Long userId, Long orderId) {
        Orders order = loadPendingOrder(userId, orderId);
        String paymentId = "pay-" + order.getId() + "-" + UUID.randomUUID();
        paymentAttemptRepository.save(new PaymentAttempt(order.getId(), paymentId));
        return new PaymentIntentResponse(paymentId, order.getTotalPrice());
    }

    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        ReservationDetailResponse reservation = reservationServiceClient.getReservation(request.reservationId());
        if (!reservation.userId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (!HOLDING_STATUS.equals(reservation.status())) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
        SeatDetailResponse seat = eventServiceClient.getSeat(reservation.seatId());
        Orders order = new Orders(
                userId,
                reservation.reservationId(),
                reservation.seatId(),
                seat.eventStartAt(),
                seat.eventId(),
                seat.price());
        return OrderResponse.from(orderRepository.save(order));
    }

    /**
     * 프론트엔드는 PortOne 결제창이 끝났다는 것만 알려 줄 뿐, 실제로 성공했는지는 알려 주지 않는다.
     * PortOne 서버에서 결제를 직접 조회해 상태와 금액을 모두 확인한 뒤에야 믿으므로, 조작된 클라이언트
     * 요청으로 주문을 공짜로 결제 처리할 수 없다.
     *
     * <p>하나의 큰 트랜잭션이 아니다. PortOne과 reservation-service를 HTTP로 호출하는데, 그 호출을 감싸는
     * 트랜잭션은 스레드가 네트워크만 기다리는 동안 DB 커넥션을 계속 붙잡는다. 대신 DB 단계마다 짧은
     * 트랜잭션을 따로 두고 네트워크 호출은 커넥션을 쥐지 않은 채 그 사이에 한다. 그래서 redeem 이후의 모든
     * 단계는 안전하게 되돌리거나 다시 시도할 수 있어야 한다. redeem은 멱등하고(Orders.benefitsRedeemedAt 참고)
     * 좌석 확정이 실패하면 보상 처리로 되돌린다.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public PaymentResponse pay(Long userId, Long orderId, PaymentRequest request) {
        PayContext ctx = transactionTemplate.execute(status -> preparePayment(userId, orderId, request));

        PortOnePaymentResponse portOnePayment = portOneClient.getPayment(request.paymentId());
        boolean verified = portOnePayment.isPaid() && portOnePayment.amount().total() == ctx.totalPrice();
        // PortOne이 알려 준 값(카드 / KAKAOPAY / NAVERPAY ...)을 기록한다. 클라이언트가 보낸 값은 쓰지 않는다.
        String method = portOnePayment.methodLabel();
        if (!verified) {
            // 청구된 것이 없으므로(PortOne이 결제 완료로 보고하지 않는다) 환불할 것도 없다.
            return failPayment(ctx, request.paymentId(), method, null, false);
        }

        try {
            transactionTemplate.executeWithoutResult(status -> orderBenefitService.redeem(loadOrder(orderId)));
        } catch (ApiException e) {
            // 고객은 이미 PortOne에 할인된 금액을 냈는데, 그 가격의 근거였던 쿠폰/포인트를 더는 지킬 수 없다
            // (다른 곳에서 썼거나 쿠폰이 소진됐다). 그래서 결제를 환불하고 주문을 실패 처리한다. 자격이 없어진
            // 가격으로 결제된 채 두지 않는다.
            log.warn("Benefits no longer valid for order {}: {}", orderId, e.getErrorCode());
            boolean refunded = refundQuietly(request.paymentId(), "쿠폰/포인트 적용 실패로 자동 환불");
            return failPayment(ctx, request.paymentId(), method, null, refunded);
        }

        // 우리 쪽 PAID 상태를 확정하기 전에 reservation-service(그쪽에서 좌석을 판매 처리한다)에서 먼저 확정한다.
        // 확정이 실패해도 주문만 결제 완료로 남고 반대편에는 대응하는 예약/좌석 상태가 없는 일이 생기지 않는다.
        try {
            reservationServiceClient.confirm(ctx.reservationId());
        } catch (ApiException e) {
            // 혜택은 방금 전에 커밋됐다. 되돌려서 주문이 이번 시도 전과 똑같은 상태가 되게 한다. 그러면 재시도가
            // 혜택이 반쯤 쓰인 것을 발견하는 대신 새로 소모한다.
            reverseRedeemedBenefits(orderId);
            if (e.getErrorCode() != ErrorCode.HOLD_EXPIRED) {
                // reservation-service에 닿지 못했거나 다른 이유로 실패했다. 좌석을 잃었다는 근거는 없으므로 주문은
                // PENDING으로 두고, 홀드가 유지되는 동안 같은 paymentId로 다시 시도할 수 있다.
                throw e;
            }
            // 결제창이 열려 있는 동안 5분 홀드가 끝났고, 좌석은 이미 풀렸거나 다른 사람이 잡았을 수 있다. 확정하면
            // 한 좌석을 두 번 파는 셈이다. 고객은 이미 결제했으므로 결제를 환불한다(쿠폰/포인트/적립은 위에서 이미 돌려줬다).
            log.warn("Hold expired before order {} could be confirmed - refunding payment {}", orderId, request.paymentId());
            boolean refunded = refundQuietly(request.paymentId(), "좌석 홀드 만료로 자동 환불");
            return failPayment(ctx, request.paymentId(), method, "HOLD_EXPIRED", refunded);
        }

        return transactionTemplate.execute(status -> completePayment(orderId, method, request.paymentId()));
    }

    /** pay()의 나머지 부분이 주문에 대해 알아야 하는 것이다. 네트워크 호출 사이에 불러온 엔티티를 붙들고 있지 않아도 된다. */
    private record PayContext(Long orderId, Long reservationId, int totalPrice) {
    }

    private PayContext preparePayment(Long userId, Long orderId, PaymentRequest request) {
        Orders order = loadOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_ALREADY_PAID);
        }
        // PortOne에 묻기 전에 먼저 확인한다. 결제 자체는 이미 클라이언트 쪽에서 일어났으므로, 환불하지 않고
        // 확정을 거절한다. 클라이언트가 구매자를 확인한 뒤 같은 paymentId로 pay()를 다시 호출하고, 그 사이
        // 주문은 PENDING으로 남는다.
        if (!identityVerificationService.isSatisfied(orderId)) {
            throw new ApiException(ErrorCode.IDENTITY_VERIFICATION_REQUIRED);
        }

        // 결제 id는 이 서버가 바로 이 주문을 위해 발급한 것이어야 한다. 이 검사가 없으면 결제된 PortOne 결제 한 건을
        // 같은 가격의 다른 모든 결제 대기 주문에 내밀 수 있다.
        boolean registered = paymentAttemptRepository
                .findByPaymentId(request.paymentId())
                .map(attempt -> attempt.getOrderId().equals(order.getId()))
                .orElse(false);
        if (!registered) {
            throw new ApiException(ErrorCode.PAYMENT_NOT_REGISTERED);
        }
        return new PayContext(order.getId(), order.getReservationId(), order.getTotalPrice());
    }

    private Orders loadOrder(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
    }

    /** 마지막 단계다. 좌석이 팔렸으니 결제를 기록하고 주문 상태를 바꾼다. 행을 잠가서 두 번의 완료가 둘 다 성공할 수 없게 한다. */
    private PaymentResponse completePayment(Long orderId, String method, String paymentId) {
        Orders order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_ALREADY_PAID);
        }
        LocalDateTime paidAt = LocalDateTime.now();
        order.markPaid();
        paymentRepository.save(new Payment(order, method, paymentId, PaymentStatus.SUCCESS, order.getTotalPrice(), paidAt));
        meterRegistry.counter("payments_total", "result", "success").increment();
        return new PaymentResponse(order.getId(), PaymentStatus.SUCCESS, paidAt, null);
    }

    /** 이미 커밋된 redeem을 되돌린다. 여기서 실패해도 되돌리게 만든 원래 오류를 가리면 안 된다. */
    private void reverseRedeemedBenefits(Long orderId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Orders order = loadOrder(orderId);
                if (order.isBenefitsRedeemed()) {
                    orderBenefitService.reverse(order, 100);
                }
            });
        } catch (RuntimeException e) {
            log.error("Could not give back the coupon/points of order {} after a failed payment - needs a look: {}", orderId, e.getMessage());
        }
    }

    /**
     * @param refunded 결제 금액을 전액 돌려줬는지 여부. 주문 목록이 "결제했다가 환불됨"과
     *     "결제가 아예 안 됨"을 구분할 수 있게 한다(조정 스윕도 환불을 같은 방식으로 기록한다)
     */
    private PaymentResponse failPayment(PayContext ctx, String paymentId, String method, String failureReason, boolean refunded) {
        reservationServiceClient.cancel(ctx.reservationId());
        transactionTemplate.executeWithoutResult(status -> {
            Orders order = loadOrder(ctx.orderId());
            order.markFailed();
            Payment record = new Payment(order, method, paymentId, PaymentStatus.FAILED, order.getTotalPrice(), null);
            if (refunded) {
                record.markRefunded(order.getTotalPrice());
            }
            paymentRepository.save(record);
        });
        meterRegistry.counter("payments_total", "result", "failed").increment();
        return new PaymentResponse(ctx.orderId(), PaymentStatus.FAILED, null, failureReason);
    }

    /** @return PortOne이 취소를 받아들였는지 여부 */
    private boolean refundQuietly(String paymentId, String reason) {
        try {
            portOneClient.cancelPayment(paymentId, reason);
            return true;
        } catch (RestClientException e) {
            // 자동으로 더 할 수 있는 것이 없다. PortOne 콘솔에서 수동으로 환불해야 한다.
            log.error("AUTO-REFUND FAILED for PortOne payment {} - refund it manually: {}", paymentId, e.getMessage());
            return false;
        }
    }

    /** 지금 이 주문을 취소하면 얼마가 환불되는지. 사용자가 확인하기 전에 주문 화면이 이 값을 보여 준다. */
    @Transactional(readOnly = true)
    public RefundQuote refundPreview(Long userId, Long orderId) {
        // 여기서는 행 잠금을 쓰지 않는다. 읽기 전용 트랜잭션에서 실행되고 MySQL은 거기서 SELECT ... FOR UPDATE를 거절하며,
        // 미리보기는 아무것도 바꾸지 않으니 보호할 것도 없다. cancelOrder가 잠금 아래에서 모든 것을 다시 확인한다.
        return RefundPolicy.quote(loadCancellableOrder(userId, orderId, false), LocalDateTime.now());
    }

    /**
     * 결제된 주문을 취소하고 {@link RefundPolicy}에 따라 PortOne으로 환불한다. 공연이 한참 남았을 때는
     * 전액, 가까워질수록 줄어드는 비율, 마지막 하루 안에서는 환불하지 않는다.
     *
     * @param expectedRefundAmount 사용자가 확인 창에서 본 금액(있다면). 구간은 "보여 준 때"와 "확인한 때"
     *     사이에 넘어갈 수 있는데, 사용자는 동의한 금액보다 적게 받아서는 안 된다
     */
    public void cancelOrder(Long userId, Long orderId, Integer expectedRefundAmount) {
        Orders order = loadCancellableOrder(userId, orderId, true);

        RefundQuote quote = RefundPolicy.quote(order, LocalDateTime.now());
        if (!quote.isCancellable()) {
            throw new ApiException(ErrorCode.REFUND_PERIOD_EXPIRED);
        }
        if (expectedRefundAmount != null && expectedRefundAmount != quote.refundAmount()) {
            throw new ApiException(ErrorCode.REFUND_QUOTE_CHANGED);
        }

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.REFUND_FAILED));
        boolean full = quote.refundPercent() == 100;
        try {
            portOneClient.cancelPayment(
                    payment.getPortonePaymentId(), "고객 요청 취소", full ? null : Long.valueOf(quote.refundAmount()));
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.REFUND_FAILED);
        }

        payment.markRefunded(quote.refundAmount());
        if (full) {
            order.cancel();
        } else {
            order.markPartiallyRefunded();
        }
        orderBenefitService.reverse(order, quote.refundPercent());
        reservationServiceClient.cancel(order.getReservationId());
    }

    /**
     * @param lock 실제 취소일 때 true. 행을 잠가서 같은 순간에 만들어지거나 수락되는 양도가 아래의 검사를
     *     빠져나가지 못하게 한다. 읽기 전용 미리보기는 false를 넘긴다.
     */
    private Orders loadCancellableOrder(Long userId, Long orderId, boolean lock) {
        Orders order = (lock ? orderRepository.findByIdForUpdate(orderId) : orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ApiException(ErrorCode.ORDER_NOT_CANCELLABLE);
        }
        if (order.wasTransferred()) {
            throw new ApiException(ErrorCode.ORDER_TRANSFERRED);
        }
        if (transferRepository.existsByPendingOrderId(orderId)) {
            throw new ApiException(ErrorCode.ORDER_TRANSFER_PENDING);
        }
        return order;
    }

    /**
     * 결제 대기 주문의 결제 화면이다. 사용자가 떠난 결제(팝업을 닫았거나 다른 곳으로 이동했거나 새로고침했다)를
     * 좌석이 아직 잡혀 있는 동안 마무리할 수 있게 한다. 홀드가 끝났다면 거절한다. 그때는 좌석이 이미 판매
     * 대기로 돌아갔고, 결제하면 고객이 더는 갖지 못하는 것에 대해 돈을 받게 된다.
     */
    @Transactional(readOnly = true)
    public CheckoutResponse resumeCheckout(Long userId, Long orderId) {
        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_NOT_PENDING);
        }
        LocalDateTime holdExpireAt = liveHoldExpiry(order, LocalDateTime.now());
        if (holdExpireAt == null) {
            throw new ApiException(ErrorCode.ORDER_NOT_RESUMABLE);
        }
        return new CheckoutResponse(OrderResponse.from(order), order.getSeatId(), order.getEventId(), holdExpireAt);
    }

    /**
     * 결제 대기 주문이 잡아 둔 좌석이 끝나는 시각. 더는 결제할 수 없는 주문이면 null이다. 주문은 예약 뒤에
     * 만들어지므로 홀드 자체보다 오래된 주문은 아무것도 잡고 있을 수 없다. 이 검사 덕분에 오래전에 버려진
     * 주문이 목록을 불러올 때마다 reservation-service 호출을 하나씩 쓰지 않는다.
     */
    private LocalDateTime liveHoldExpiry(Orders order, LocalDateTime now) {
        if (order.getStatus() != OrderStatus.PENDING || order.getCreatedAt().isBefore(now.minus(MAX_HOLD_AGE))) {
            return null;
        }
        try {
            ReservationDetailResponse reservation = reservationServiceClient.getReservation(order.getReservationId());
            boolean live = HOLDING_STATUS.equals(reservation.status()) && reservation.holdExpireAt().isAfter(now);
            return live ? reservation.holdExpireAt() : null;
        } catch (RuntimeException e) {
            // reservation-service에 닿지 못했다. 보증할 수 없는 결제를 권하는 것보다 "만료"로 보여 주는 편이 낫다.
            return null;
        }
    }

    @Transactional(readOnly = true)
    public OrderHistoryListResponse getOrders(Long userId, Pageable pageable) {
        Page<Orders> page = orderRepository.findByUserId(userId, pageable);
        List<Orders> orders = page.getContent();

        List<Long> seatIds = orders.stream().map(Orders::getSeatId).distinct().toList();
        Map<Long, SeatDetailResponse> seatsById = seatIds.isEmpty()
                ? Map.of()
                : eventServiceClient.getSeats(seatIds).stream()
                        .collect(Collectors.toMap(SeatDetailResponse::seatId, Function.identity()));

        List<Long> orderIds = orders.stream().map(Orders::getId).toList();
        Map<Long, Payment> paymentsByOrderId = orderIds.isEmpty()
                ? Map.of()
                : paymentRepository.findByOrderIdIn(orderIds).stream()
                        .collect(Collectors.toMap(p -> p.getOrder().getId(), Function.identity()));

        Set<Long> pendingTransfers = orderIds.isEmpty() ? Set.of() : transferRepository.findPendingOrderIds(orderIds);
        LocalDateTime now = LocalDateTime.now();

        List<OrderHistoryResponse> content = orders.stream()
                .map(order -> OrderHistoryResponse.from(
                        order,
                        seatsById.get(order.getSeatId()),
                        paymentsByOrderId.get(order.getId()),
                        order.wasTransferred() ? "TRANSFERRED" : pendingTransfers.contains(order.getId()) ? "PENDING" : null,
                        transferPolicy.isTransferable(order, pendingTransfers.contains(order.getId()), now),
                        liveHoldExpiry(order, now)))
                .toList();
        return new OrderHistoryListResponse(content, page.getTotalElements());
    }
}
