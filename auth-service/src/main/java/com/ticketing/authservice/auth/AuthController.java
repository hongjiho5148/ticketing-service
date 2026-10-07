package com.ticketing.authservice.auth;

import com.ticketing.authservice.auth.dto.AdminLoginResponse;
import com.ticketing.authservice.auth.dto.ChangePasswordRequest;
import com.ticketing.authservice.auth.dto.LoginRequest;
import com.ticketing.authservice.auth.dto.LoginResponse;
import com.ticketing.authservice.auth.dto.MeResponse;
import com.ticketing.authservice.auth.dto.ResendVerificationRequest;
import com.ticketing.authservice.auth.dto.SignupRequest;
import com.ticketing.authservice.auth.dto.SignupResponse;
import com.ticketing.authservice.auth.dto.UpdateProfileRequest;
import com.ticketing.authservice.captcha.CaptchaVerifier;
import com.ticketing.authservice.common.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaVerifier captchaVerifier;
    private final String frontendUrl;

    public AuthController(
            AuthService authService, CaptchaVerifier captchaVerifier, @Value("${app.frontend-url}") String frontendUrl) {
        this.authService = authService;
        this.captchaVerifier = captchaVerifier;
        this.frontendUrl = frontendUrl;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Captcha-Token", required = false) String captchaToken) {
        // Before the password is even looked at, so a bot can't use this endpoint to test credentials without solving it.
        captchaVerifier.verify(captchaToken);
        return ResponseEntity.ok(authService.login(request));
    }

    // Always 202: whether the address exists (or is already verified) is deliberately not revealed.
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/admin/login")
    public ResponseEntity<AdminLoginResponse> adminLogin(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.adminLogin(request));
    }

    @GetMapping("/verify-email")
    public void verifyEmail(@RequestParam String token, HttpServletResponse response) throws IOException {
        try {
            authService.verifyEmail(token);
            response.sendRedirect(frontendUrl + "/login?verified=true");
        } catch (ApiException e) {
            response.sendRedirect(frontendUrl + "/login?verified=false");
        }
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me() {
        return ResponseEntity.ok(authService.getMe(SecurityUtil.getCurrentUserId()));
    }

    @PatchMapping("/me")
    public ResponseEntity<MeResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(authService.updateProfile(SecurityUtil.getCurrentUserId(), request));
    }

    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(SecurityUtil.getCurrentUserId(), request);
        return ResponseEntity.noContent().build();
    }
}
