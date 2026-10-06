package com.ticketing.orderservice.point;

import com.ticketing.orderservice.auth.SecurityUtil;
import com.ticketing.orderservice.point.dto.PointSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Under /api/orders/** (not /api/account/**) so the existing order-service gateway route already covers it.
@RestController
@RequestMapping("/api/orders/points")
public class PointController {

    private final PointService pointService;

    public PointController(PointService pointService) {
        this.pointService = pointService;
    }

    @GetMapping
    public PointSummaryResponse mine() {
        Long userId = SecurityUtil.getCurrentUserId();
        return new PointSummaryResponse(
                pointService.balance(userId),
                pointService.history(userId).stream().map(PointSummaryResponse.Entry::from).toList());
    }
}
