package com.ticketing.orderservice.coupon;

import com.ticketing.orderservice.coupon.dto.CouponCreateRequest;
import com.ticketing.orderservice.coupon.dto.CouponResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ROLE_ADMIN만 접근할 수 있다. 이 제한은 이 클래스가 아니라 SecurityConfig의 /api/admin/** 매처가 건다. */
@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponController {

    private final CouponService couponService;

    public AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(couponService.create(request));
    }

    @GetMapping
    public List<CouponResponse> list() {
        return couponService.listAll();
    }
}
