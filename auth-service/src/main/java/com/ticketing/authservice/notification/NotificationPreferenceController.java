package com.ticketing.authservice.notification;

import com.ticketing.authservice.auth.SecurityUtil;
import com.ticketing.authservice.notification.dto.NotificationPreferenceResponse;
import com.ticketing.authservice.notification.dto.UpdateNotificationPreferenceRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// /api/account/**가 아니라 /api/auth/** 아래에 두어서 새 라우트를 만들지 않고 기존 auth-service 게이트웨이 라우트를
// 탄다. auth-service는 셀프서비스 계정 엔드포인트(/api/auth/me, /api/auth/password)를 이미 모두 같은 접두사로 맡고 있다.
@RestController
@RequestMapping("/api/auth/notification-prefs")
public class NotificationPreferenceController {

    private final NotificationPreferenceService notificationPreferenceService;

    public NotificationPreferenceController(NotificationPreferenceService notificationPreferenceService) {
        this.notificationPreferenceService = notificationPreferenceService;
    }

    @GetMapping
    public NotificationPreferenceResponse get() {
        return new NotificationPreferenceResponse(
                notificationPreferenceService.isEmailOptedIn(SecurityUtil.getCurrentUserId()));
    }

    @PutMapping
    public NotificationPreferenceResponse update(@Valid @RequestBody UpdateNotificationPreferenceRequest request) {
        Long userId = SecurityUtil.getCurrentUserId();
        notificationPreferenceService.updateEmailOptIn(userId, request.emailOptIn());
        return new NotificationPreferenceResponse(notificationPreferenceService.isEmailOptedIn(userId));
    }
}
