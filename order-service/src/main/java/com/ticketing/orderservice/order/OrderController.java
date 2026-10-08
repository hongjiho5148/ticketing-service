package com.ticketing.orderservice.order;

import com.ticketing.orderservice.auth.SecurityUtil;
import com.ticketing.orderservice.order.dto.ApplyCouponRequest;
import com.ticketing.orderservice.order.dto.ApplyPointsRequest;
import com.ticketing.orderservice.order.dto.CheckoutResponse;
import com.ticketing.orderservice.order.dto.OrderCreateRequest;
import com.ticketing.orderservice.order.dto.OrderHistoryListResponse;
import com.ticketing.orderservice.order.dto.OrderResponse;
import com.ticketing.orderservice.order.dto.PaymentRequest;
import com.ticketing.orderservice.order.dto.RefundPreviewResponse;
import com.ticketing.orderservice.order.dto.PaymentResponse;
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

    @PostMapping("/{orderId}/apply-coupon")
    public OrderResponse applyCoupon(@PathVariable Long orderId, @Valid @RequestBody ApplyCouponRequest request) {
        return orderService.applyCoupon(SecurityUtil.getCurrentUserId(), orderId, request.code());
    }

    @DeleteMapping("/{orderId}/coupon")
    public OrderResponse removeCoupon(@PathVariable Long orderId) {
        return orderService.removeCoupon(SecurityUtil.getCurrentUserId(), orderId);
    }

    @PostMapping("/{orderId}/apply-points")
    public OrderResponse applyPoints(@PathVariable Long orderId, @Valid @RequestBody ApplyPointsRequest request) {
        return orderService.applyPoints(SecurityUtil.getCurrentUserId(), orderId, request.points());
    }

    @GetMapping("/{orderId}/checkout")
    public CheckoutResponse resumeCheckout(@PathVariable Long orderId) {
        return orderService.resumeCheckout(SecurityUtil.getCurrentUserId(), orderId);
    }

    @GetMapping("/{orderId}/refund-preview")
    public RefundPreviewResponse refundPreview(@PathVariable Long orderId) {
        return RefundPreviewResponse.from(orderService.refundPreview(SecurityUtil.getCurrentUserId(), orderId));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancelOrder(
            @PathVariable Long orderId, @RequestParam(required = false) Integer expectedRefund) {
        orderService.cancelOrder(SecurityUtil.getCurrentUserId(), orderId, expectedRefund);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public OrderHistoryListResponse getOrders(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return orderService.getOrders(SecurityUtil.getCurrentUserId(), pageable);
    }
}
