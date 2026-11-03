package com.ticketing.authservice.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설정을 기본값에서 실제로 바꾼 사용자마다 한 행이다. 이것을 건드린 적 없는 사용자는 행이 전혀 없이도
 * 수신 동의 상태로 남는다(NotificationPreferenceService 참고). 그래서 새 알림 종류가 추가되어도
 * 이 테이블을 채워 넣을 필요가 없다.
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
