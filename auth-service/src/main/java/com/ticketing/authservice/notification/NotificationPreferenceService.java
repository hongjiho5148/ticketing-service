package com.ticketing.authservice.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository notificationPreferenceRepository;

    public NotificationPreferenceService(NotificationPreferenceRepository notificationPreferenceRepository) {
        this.notificationPreferenceRepository = notificationPreferenceRepository;
    }

    // No row yet = never changed the default, which is opted in.
    @Transactional(readOnly = true)
    public boolean isEmailOptedIn(Long userId) {
        return notificationPreferenceRepository.findById(userId)
                .map(NotificationPreference::isEmailOptIn)
                .orElse(true);
    }

    public void updateEmailOptIn(Long userId, boolean emailOptIn) {
        NotificationPreference preference = notificationPreferenceRepository.findById(userId)
                .orElseGet(() -> new NotificationPreference(userId, emailOptIn));
        preference.updateEmailOptIn(emailOptIn);
        notificationPreferenceRepository.save(preference);
    }
}
