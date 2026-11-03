package com.ticketing.orderservice.point;

import com.ticketing.orderservice.auth.SecurityUtil;
import com.ticketing.orderservice.point.dto.PointSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// /api/account/**가 아니라 /api/orders/** 아래에 두어서 기존 order-service 게이트웨이 라우트가 그대로 적용된다.
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
