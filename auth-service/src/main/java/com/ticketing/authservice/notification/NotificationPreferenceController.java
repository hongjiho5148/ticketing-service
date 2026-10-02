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

// Nested under /api/auth/** (not /api/account/**) so it rides the existing auth-service gateway
// route instead of needing a new one - auth-service already owns every other self-service account
// endpoint (/api/auth/me, /api/auth/password) under this same prefix.
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
