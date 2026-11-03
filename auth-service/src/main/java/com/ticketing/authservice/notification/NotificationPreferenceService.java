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

    // 행이 아직 없다는 것은 기본값을 바꾼 적이 없다는 뜻이고, 기본값은 수신 동의다.
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
