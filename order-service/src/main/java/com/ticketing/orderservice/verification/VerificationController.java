package com.ticketing.orderservice.verification;

import com.ticketing.orderservice.auth.SecurityUtil;
import com.ticketing.orderservice.verification.dto.VerifyIdentityRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// /api/orders 아래에 두어서 게이트웨이의 기존 order-service 라우트가 그대로 적용된다.
@RestController
@RequestMapping("/api/orders")
public class VerificationController {

    private final IdentityVerificationService verificationService;

    public VerificationController(IdentityVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping("/{orderId}/verify-identity")
    public ResponseEntity<Void> verify(@PathVariable Long orderId, @RequestBody VerifyIdentityRequest request) {
        try {
            verificationService.verifyForOrder(SecurityUtil.getCurrentUserId(), orderId, request);
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 요청(더블 클릭)이 먼저 기록했다. 어느 쪽이든 주문은 확인된 상태다.
        }
        return ResponseEntity.noContent().build();
    }
}
