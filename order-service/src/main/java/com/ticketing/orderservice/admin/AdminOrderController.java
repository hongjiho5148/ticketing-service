package com.ticketing.orderservice.admin;

import com.ticketing.orderservice.admin.dto.OrderSummaryResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gated to ROLE_ADMIN by the /api/admin/** matcher in SecurityConfig, not by anything in here. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    @GetMapping("/summary")
    public List<OrderSummaryResponse> summary() {
        return adminOrderService.summaryByEvent();
    }
}
