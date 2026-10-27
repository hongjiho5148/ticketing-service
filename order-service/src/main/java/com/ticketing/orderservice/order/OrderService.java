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

    // reservation-service holds a seat for 5 minutes; the extra minute is slack for clock differences.
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
     * Registers a fresh payment id for this order. pay() later accepts only ids registered here, so a payment made for
     * one order cannot be presented for another, and the reconciler knows which PortOne payments to look up.
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
     * The frontend only tells us a PortOne payment window finished - never whether it actually
     * succeeded. We look the payment up on PortOne's own server and check both its status and
     * amount before trusting it, so a tampered client request can't mark an order paid for free.
     *
     * <p>Not one big transaction: it calls PortOne and reservation-service over HTTP, and a transaction around those
     * calls would keep a DB connection checked out while the thread just waits on the network. Instead each database
     * step is its own short transaction and the network calls run in between with no connection held. That makes
     * every step after the redeem something that has to be undone or retried safely: the redeem is idempotent
     * (see Orders.benefitsRedeemedAt) and is compensated when the seat confirmation fails.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public PaymentResponse pay(Long userId, Long orderId, PaymentRequest request) {
        PayContext ctx = transactionTemplate.execute(status -> preparePayment(userId, orderId, request));

        PortOnePaymentResponse portOnePayment = portOneClient.getPayment(request.paymentId());
        boolean verified = portOnePayment.isPaid() && portOnePayment.amount().total() == ctx.totalPrice();
        // Recorded from PortOne's own report (card / KAKAOPAY / NAVERPAY ...), not from anything the client sent.
        String method = portOnePayment.methodLabel();
        if (!verified) {
            return failPayment(ctx, request.paymentId(), method, null);
        }

        try {
            transactionTemplate.executeWithoutResult(status -> orderBenefitService.redeem(loadOrder(orderId)));
        } catch (ApiException e) {
            // The customer already paid PortOne the discounted amount, but the coupon/points that
            // justified that price can't be honored any more (spent elsewhere, coupon ran out) -
            // so the payment gets refunded and the order fails instead of being paid at a price
            // it no longer qualifies for.
            log.warn("Benefits no longer valid for order {}: {}", orderId, e.getErrorCode());
            refundQuietly(request.paymentId(), "쿠폰/포인트 적용 실패로 자동 환불");
            return failPayment(ctx, request.paymentId(), method, null);
        }

        // Confirm on reservation-service (which sells the seat on its end) before committing our
        // own PAID status locally, so a failed confirm never leaves an order marked paid with no
        // matching reservation/seat state on the other side.
        try {
            reservationServiceClient.confirm(ctx.reservationId());
        } catch (ApiException e) {
            // The benefits were committed a moment ago; give them back so the order is exactly as it was before this
            // attempt - a retry then redeems them afresh instead of finding them half-used.
            reverseRedeemedBenefits(orderId);
            if (e.getErrorCode() != ErrorCode.HOLD_EXPIRED) {
                // reservation-service is unreachable or failed for another reason: nothing says the seat is
                // lost, so the order stays PENDING and the same paymentId can be retried while the hold lasts.
                throw e;
            }
            // The 5-minute hold ran out while the payment window was open, and the seat may already be free
            // or held by someone else - confirming would sell one seat twice. The customer has paid, so
            // refund the payment (the coupon/points/reward were already given back above).
            log.warn("Hold expired before order {} could be confirmed - refunding payment {}", orderId, request.paymentId());
            refundQuietly(request.paymentId(), "좌석 홀드 만료로 자동 환불");
            return failPayment(ctx, request.paymentId(), method, "HOLD_EXPIRED");
        }

        return transactionTemplate.execute(status -> completePayment(orderId, method, request.paymentId()));
    }

    /** What the rest of pay() needs to know about the order, so it never has to hold a loaded entity across a network call. */
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
        // Checked before PortOne is even consulted. The charge itself already happened client-side, so
        // this refuses to confirm rather than refunds: the client verifies the buyer and retries pay()
        // with the same paymentId, and the order stays PENDING in between.
        if (!identityVerificationService.isSatisfied(orderId)) {
            throw new ApiException(ErrorCode.IDENTITY_VERIFICATION_REQUIRED);
        }

        // The payment id must be one this server issued for THIS order. Without it a single paid PortOne payment could be
        // presented for every other pending order of the same price.
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

    /** The last step: the seat is sold, so record the payment and flip the order. Row-locked so two completions cannot both win. */
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

    /** Undoes a redeem that was already committed. A failure here must not hide the error that made us undo it. */
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

    private PaymentResponse failPayment(PayContext ctx, String paymentId, String method, String failureReason) {
        reservationServiceClient.cancel(ctx.reservationId());
        transactionTemplate.executeWithoutResult(status -> {
            Orders order = loadOrder(ctx.orderId());
            order.markFailed();
            paymentRepository.save(new Payment(order, method, paymentId, PaymentStatus.FAILED, order.getTotalPrice(), null));
        });
        meterRegistry.counter("payments_total", "result", "failed").increment();
        return new PaymentResponse(ctx.orderId(), PaymentStatus.FAILED, null, failureReason);
    }

    private void refundQuietly(String paymentId, String reason) {
        try {
            portOneClient.cancelPayment(paymentId, reason);
        } catch (RestClientException e) {
            // Nothing more to do automatically - needs a manual refund in the PortOne console.
            log.error("AUTO-REFUND FAILED for PortOne payment {} - refund it manually: {}", paymentId, e.getMessage());
        }
    }

    /** What cancelling this order right now would refund - the order page shows this before the user confirms. */
    @Transactional(readOnly = true)
    public RefundQuote refundPreview(Long userId, Long orderId) {
        // No row lock here: this runs in a read-only transaction, where MySQL refuses SELECT ... FOR UPDATE,
        // and a preview changes nothing so there is nothing to protect. cancelOrder re-checks everything under the lock.
        return RefundPolicy.quote(loadCancellableOrder(userId, orderId, false), LocalDateTime.now());
    }

    /**
     * Cancels a paid order and refunds it through PortOne per {@link RefundPolicy}: the full amount
     * well ahead of the show, a shrinking share as it gets closer, and not at all inside the last day.
     *
     * @param expectedRefundAmount the amount the user was shown in the confirmation dialog, if any - the
     *     tier can tick over between "shown" and "confirmed", and the user must never get less than
     *     what they agreed to
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
     * @param lock true for the real cancellation: the row is locked so a transfer being created or accepted
     *     at the same moment can't slip past the checks below. The read-only preview passes false.
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
     * The checkout of a pending order, so a payment the user walked away from (closed the popup, navigated
     * off, refreshed) can be finished while the seat is still held. Refused once the hold has lapsed - the seat is
     * back on sale by then and paying for it would charge the customer for something they no longer have.
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
     * When the held seat of a pending order runs out, or null if the order can no longer be paid. An order is
     * created after its reservation, so one older than the hold itself cannot still be holding anything - that
     * check keeps old abandoned orders from each costing a call to reservation-service on every list load.
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
            // reservation-service unreachable: better to show "expired" than to offer a payment we can't vouch for.
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
