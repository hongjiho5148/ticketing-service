package com.ticketing.backend.order;

import com.ticketing.backend.common.ApiException;
import com.ticketing.backend.common.ErrorCode;
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
import com.ticketing.backend.reservation.Reservation;
import com.ticketing.backend.reservation.ReservationRepository;
import com.ticketing.backend.reservation.ReservationStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@Service
@Transactional
public class OrderService {

    private static final Duration REFUND_CUTOFF_BEFORE_EVENT = Duration.ofHours(24);

    private final OrderRepository orderRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;

    public OrderService(
            OrderRepository orderRepository,
            ReservationRepository reservationRepository,
            PaymentRepository paymentRepository,
            PortOneClient portOneClient) {
        this.orderRepository = orderRepository;
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.portOneClient = portOneClient;
    }

    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        Reservation reservation = reservationRepository.findById(request.reservationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (reservation.getStatus() != ReservationStatus.HOLDING) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
        Orders order = new Orders(userId, reservation, reservation.getSeat().getPrice());
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
            order.markPaid();
            order.getReservation().confirm();
            order.getReservation().getSeat().sell();
            paymentRepository.save(
                    new Payment(order, "CARD", request.paymentId(), PaymentStatus.SUCCESS, order.getTotalPrice(), paidAt));
            return new PaymentResponse(order.getId(), PaymentStatus.SUCCESS, paidAt);
        }

        order.markFailed();
        order.getReservation().cancel();
        order.getReservation().getSeat().release();
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

        LocalDateTime eventStartAt = order.getReservation().getSeat().getEvent().getStartAt();
        if (LocalDateTime.now().isAfter(eventStartAt.minus(REFUND_CUTOFF_BEFORE_EVENT))) {
            throw new ApiException(ErrorCode.REFUND_PERIOD_EXPIRED);
        }

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.REFUND_FAILED));
        try {
            portOneClient.cancelPayment(payment.getPortonePaymentId(), "고객 요청 취소");
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.REFUND_FAILED);
        }

        order.cancel();
        order.getReservation().cancel();
        order.getReservation().getSeat().release();
    }

    @Transactional(readOnly = true)
    public OrderHistoryListResponse getOrders(Long userId, Pageable pageable) {
        Page<Orders> page = orderRepository.findByUserId(userId, pageable);
        return new OrderHistoryListResponse(page.getContent().stream().map(OrderHistoryResponse::from).toList());
    }
}
