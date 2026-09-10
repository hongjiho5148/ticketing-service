package com.ticketing.backend.order;

import com.ticketing.backend.auth.SecurityUtil;
import com.ticketing.backend.order.dto.OrderCreateRequest;
import com.ticketing.backend.order.dto.OrderHistoryListResponse;
import com.ticketing.backend.order.dto.OrderResponse;
import com.ticketing.backend.order.dto.PaymentRequest;
import com.ticketing.backend.order.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = orderService.createOrder(SecurityUtil.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{orderId}/payment")
    public ResponseEntity<PaymentResponse> pay(@PathVariable Long orderId, @Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.ok(orderService.pay(SecurityUtil.getCurrentUserId(), orderId, request));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable Long orderId) {
        orderService.cancelOrder(SecurityUtil.getCurrentUserId(), orderId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public OrderHistoryListResponse getOrders(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return orderService.getOrders(SecurityUtil.getCurrentUserId(), pageable);
    }
}
