package com.ticketing.eventservice.alert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WaitlistSubscriptionRepository extends JpaRepository<WaitlistSubscription, Long> {

    Optional<WaitlistSubscription> findByUserIdAndEventId(Long userId, Long eventId);

    void deleteByUserIdAndEventId(Long userId, Long eventId);

    List<WaitlistSubscription> findByEventIdAndNotifiedAtIsNullOrderByIdAsc(Long eventId, Pageable pageable);

    /**
     * Claims a subscription for notification. Of several concurrent seat releases, only the one
     * whose UPDATE flips notified_at from NULL gets rows-affected = 1 and sends the mail.
     */
    @Modifying
    @Transactional
    @Query("update WaitlistSubscription s set s.notifiedAt = :now where s.id = :id and s.notifiedAt is null")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Puts a claimed subscription back in line after its mail failed to send. */
    @Modifying
    @Transactional
    @Query("update WaitlistSubscription s set s.notifiedAt = null where s.id = :id")
    int release(@Param("id") Long id);
}
