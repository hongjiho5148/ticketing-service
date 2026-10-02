package com.ticketing.authservice.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row per user who has actually changed a setting away from the default - a user who never
 * touches this stays opted in with no row at all (see NotificationPreferenceService), so this
 * table never needs backfilling as new notification types are added.
 */
@Entity
@Table(name = "notification_preference")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "email_opt_in", nullable = false)
    private boolean emailOptIn;

    public NotificationPreference(Long userId, boolean emailOptIn) {
        this.userId = userId;
        this.emailOptIn = emailOptIn;
    }

    public void updateEmailOptIn(boolean emailOptIn) {
        this.emailOptIn = emailOptIn;
    }
}
