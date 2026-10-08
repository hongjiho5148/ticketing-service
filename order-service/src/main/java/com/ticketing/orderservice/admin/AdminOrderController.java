package com.ticketing.orderservice.admin;

import com.ticketing.orderservice.admin.dto.AdminOrderListResponse;
import com.ticketing.orderservice.admin.dto.OrderSummaryResponse;
import com.ticketing.orderservice.order.OrderStatus;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gated to ROLE_ADMIN by the /api/admin/** matcher in SecurityConfig, not by anything in here. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    /** Newest first; {@code status} omitted = every status. */
    @GetMapping
    public AdminOrderListResponse list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return adminOrderService.listOrders(
                status, PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/summary")
    public List<OrderSummaryResponse> summary() {
        return adminOrderService.summaryByEvent();
    }
}
