package com.ticketing.orderservice.coupon;

import com.ticketing.orderservice.auth.SecurityUtil;
import com.ticketing.orderservice.coupon.dto.AvailableCouponResponse;
import com.ticketing.orderservice.coupon.dto.CouponHistoryResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Under /api/orders/** so the existing order-service gateway route already covers it.
@RestController
@RequestMapping("/api/orders/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping
    public List<CouponHistoryResponse> history() {
        return couponService.history(SecurityUtil.getCurrentUserId());
    }

    @GetMapping("/available")
    public List<AvailableCouponResponse> available() {
        return couponService.available(SecurityUtil.getCurrentUserId());
    }
}
