package com.ticketing.backend.order;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
import com.ticketing.backend.eventclient.EventServiceClient;
import com.ticketing.backend.eventclient.dto.SeatDetailResponse;
import com.ticketing.backend.order.dto.OrderCreateRequest;
import com.ticketing.backend.order.dto.OrderHistoryListResponse;
import com.ticketing.backend.order.dto.OrderHistoryResponse;
import com.ticketing.backend.order.dto.OrderResponse;
import com.ticketing.backend.order.dto.PaymentRequest;
import com.ticketing.backend.order.dto.PaymentResponse;
import com.ticketing.backend.payment.Payment;
import com.ticketing.backend.payment.PaymentRepository;
import com.ticketing.backend.payment.PaymentStatus;
import com.ticketing.backend.payment.portone.PortOneClient;
import com.ticketing.backend.payment.portone.PortOnePaymentResponse;
import com.ticketing.backend.reservationclient.ReservationServiceClient;
import com.ticketing.backend.reservationclient.dto.ReservationDetailResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@Service
@Transactional
public class OrderService {

    private static final Duration REFUND_CUTOFF_BEFORE_EVENT = Duration.ofHours(24);
    private static final String HOLDING_STATUS = "HOLDING";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final EventServiceClient eventServiceClient;
    private final ReservationServiceClient reservationServiceClient;

    public OrderService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            PortOneClient portOneClient,
            EventServiceClient eventServiceClient,
            ReservationServiceClient reservationServiceClient) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.portOneClient = portOneClient;
        this.eventServiceClient = eventServiceClient;
        this.reservationServiceClient = reservationServiceClient;
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
        Orders order = new Orders(userId, reservation.reservationId(), reservation.seatId(), seat.price());
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

        if (verified) {
            LocalDateTime paidAt = LocalDateTime.now();
            // Confirm on reservation-service (which sells the seat on its end) before committing our
            // own PAID status locally, so a failed confirm never leaves an order marked paid with no
            // matching reservation/seat state on the other side.
            reservationServiceClient.confirm(order.getReservationId());
            order.markPaid();
            paymentRepository.save(
                    new Payment(order, "CARD", request.paymentId(), PaymentStatus.SUCCESS, order.getTotalPrice(), paidAt));
            return new PaymentResponse(order.getId(), PaymentStatus.SUCCESS, paidAt);
        }

        reservationServiceClient.cancel(order.getReservationId());
        order.markFailed();
        paymentRepository.save(
                new Payment(order, "CARD", request.paymentId(), PaymentStatus.FAILED, order.getTotalPrice(), null));
        return new PaymentResponse(order.getId(), PaymentStatus.FAILED, null);
    }

    /**
     * Cancels a paid order and refunds it through PortOne. Only allowed up to
     * {@link #REFUND_CUTOFF_BEFORE_EVENT} before the show starts, matching common ticketing
     * refund policies.
     */
    public void cancelOrder(Long userId, Long orderId) {
        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ApiException(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        SeatDetailResponse seat = eventServiceClient.getSeat(order.getSeatId());
        if (LocalDateTime.now().isAfter(seat.eventStartAt().minus(REFUND_CUTOFF_BEFORE_EVENT))) {
            throw new ApiException(ErrorCode.REFUND_PERIOD_EXPIRED);
        }

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.REFUND_FAILED));
        try {
            portOneClient.cancelPayment(payment.getPortonePaymentId(), "고객 요청 취소");
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.REFUND_FAILED);
        }

        order.cancel();
        reservationServiceClient.cancel(order.getReservationId());
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

        List<OrderHistoryResponse> content = orders.stream()
                .map(order -> OrderHistoryResponse.from(order, seatsById.get(order.getSeatId())))
                .toList();
        return new OrderHistoryListResponse(content);
    }
}
