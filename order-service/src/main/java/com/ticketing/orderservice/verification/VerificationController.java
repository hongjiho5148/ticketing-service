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

// Under /api/orders so the gateway's existing order-service route already covers it.
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
            // A concurrent request (a double click) recorded it first - the order is verified either way.
        }
        return ResponseEntity.noContent().build();
    }
}
