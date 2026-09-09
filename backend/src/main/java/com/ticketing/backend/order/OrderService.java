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
import com.ticketing.backend.reservation.Reservation;
import com.ticketing.backend.reservation.ReservationRepository;
import com.ticketing.backend.reservation.ReservationStatus;
import com.ticketing.backend.user.User;
import com.ticketing.backend.user.UserRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;

    public OrderService(
            OrderRepository orderRepository,
            ReservationRepository reservationRepository,
            UserRepository userRepository,
            PaymentRepository paymentRepository) {
        this.orderRepository = orderRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
    }

    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
        Reservation reservation = reservationRepository.findById(request.reservationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (!reservation.getUser().getId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (reservation.getStatus() != ReservationStatus.HOLDING) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
        Orders order = new Orders(user, reservation, reservation.getSeat().getPrice());
        return OrderResponse.from(orderRepository.save(order));
    }

    public PaymentResponse pay(Long userId, Long orderId, PaymentRequest request) {
        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUser().getId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_ALREADY_PAID);
        }

        LocalDateTime paidAt = LocalDateTime.now();
        order.markPaid();
        order.getReservation().confirm();
        order.getReservation().getSeat().sell();
        paymentRepository.save(new Payment(order, request.method(), PaymentStatus.SUCCESS, order.getTotalPrice(), paidAt));

        return new PaymentResponse(order.getId(), PaymentStatus.SUCCESS, paidAt);
    }

    @Transactional(readOnly = true)
    public OrderHistoryListResponse getOrders(Long userId, Pageable pageable) {
        Page<Orders> page = orderRepository.findByUserId(userId, pageable);
        return new OrderHistoryListResponse(page.getContent().stream().map(OrderHistoryResponse::from).toList());
    }
}
