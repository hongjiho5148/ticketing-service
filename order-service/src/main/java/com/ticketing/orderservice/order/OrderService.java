package com.ticketing.orderservice.order;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.dto.OrderCreateRequest;
import com.ticketing.orderservice.order.dto.OrderHistoryListResponse;
import com.ticketing.orderservice.order.dto.OrderHistoryResponse;
import com.ticketing.orderservice.order.dto.OrderResponse;
import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.order.dto.PaymentResponse;
import com.ticketing.orderservice.payment.Payment;
import com.ticketing.orderservice.payment.PaymentRepository;
import com.ticketing.orderservice.payment.PaymentStatus;
import com.ticketing.orderservice.payment.portone.PortOneClient;
import com.ticketing.orderservice.payment.portone.PortOnePaymentResponse;
import com.ticketing.orderservice.reservationclient.ReservationServiceClient;
import com.ticketing.orderservice.reservationclient.dto.ReservationDetailResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@Service
@Transactional
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String HOLDING_STATUS = "HOLDING";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final EventServiceClient eventServiceClient;
    private final ReservationServiceClient reservationServiceClient;
    private final OrderBenefitService orderBenefitService;

    public OrderService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PortOneClient portOneClient,
            EventServiceClient eventServiceClient,
            ReservationServiceClient reservationServiceClient,
            OrderBenefitService orderBenefitService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.portOneClient = portOneClient;
        this.eventServiceClient = eventServiceClient;
        this.reservationServiceClient = reservationServiceClient;
        this.orderBenefitService = orderBenefitService;
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
     */
    public PaymentResponse pay(Long userId, Long orderId, PaymentRequest request) {
        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_ALREADY_PAID);
        }

        PortOnePaymentResponse portOnePayment = portOneClient.getPayment(request.paymentId());
        boolean verified = portOnePayment.isPaid() && portOnePayment.amount().total() == order.getTotalPrice();
        // Recorded from PortOne's own report (card / KAKAOPAY / NAVERPAY ...), not from anything the client sent.
        String method = portOnePayment.methodLabel();

        if (verified) {
            try {
                orderBenefitService.redeem(order);
            } catch (ApiException e) {
                // The customer already paid PortOne the discounted amount, but the coupon/points that
                // justified that price can't be honored any more (spent elsewhere, coupon ran out) -
                // so the payment gets refunded and the order fails instead of being paid at a price
                // it no longer qualifies for.
                log.warn("Benefits no longer valid for order {}: {}", orderId, e.getErrorCode());
                refundQuietly(request.paymentId());
                return failPayment(order, request.paymentId(), method);
            }
            LocalDateTime paidAt = LocalDateTime.now();
            // Confirm on reservation-service (which sells the seat on its end) before committing our
            // own PAID status locally, so a failed confirm never leaves an order marked paid with no
            // matching reservation/seat state on the other side.
            reservationServiceClient.confirm(order.getReservationId());
            order.markPaid();
            paymentRepository.save(
                    new Payment(order, method, request.paymentId(), PaymentStatus.SUCCESS, order.getTotalPrice(), paidAt));
            return new PaymentResponse(order.getId(), PaymentStatus.SUCCESS, paidAt);
        }

        return failPayment(order, request.paymentId(), method);
    }

    private PaymentResponse failPayment(Orders order, String paymentId, String method) {
        reservationServiceClient.cancel(order.getReservationId());
        order.markFailed();
        paymentRepository.save(new Payment(order, method, paymentId, PaymentStatus.FAILED, order.getTotalPrice(), null));
        return new PaymentResponse(order.getId(), PaymentStatus.FAILED, null);
    }

    private void refundQuietly(String paymentId) {
        try {
            portOneClient.cancelPayment(paymentId, "쿠폰/포인트 적용 실패로 자동 환불");
        } catch (RestClientException e) {
            // Nothing more to do automatically - needs a manual refund in the PortOne console.
            log.error("AUTO-REFUND FAILED for PortOne payment {} - refund it manually: {}", paymentId, e.getMessage());
        }
    }

    /** What cancelling this order right now would refund - the order page shows this before the user confirms. */
    @Transactional(readOnly = true)
    public RefundQuote refundPreview(Long userId, Long orderId) {
        return RefundPolicy.quote(loadCancellableOrder(userId, orderId), LocalDateTime.now());
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
        Orders order = loadCancellableOrder(userId, orderId);

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

    private Orders loadCancellableOrder(Long userId, Long orderId) {
        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ApiException(ErrorCode.ORDER_NOT_CANCELLABLE);
        }
        return order;
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

        List<OrderHistoryResponse> content = orders.stream()
                .map(order -> OrderHistoryResponse.from(
                        order, seatsById.get(order.getSeatId()), paymentsByOrderId.get(order.getId())))
                .toList();
        return new OrderHistoryListResponse(content, page.getTotalElements());
    }
}
