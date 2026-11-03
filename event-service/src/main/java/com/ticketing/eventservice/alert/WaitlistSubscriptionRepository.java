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
     * 알림을 보내기 위해 구독을 가져간다. 동시에 일어난 여러 좌석 해제 중 notified_at을 NULL에서 바꾸는
     * UPDATE를 한 쪽만 영향받은 행 수가 1이 되어 메일을 보낸다.
     */
    @Modifying
    @Transactional
    @Query("update WaitlistSubscription s set s.notifiedAt = :now where s.id = :id and s.notifiedAt is null")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 메일 전송에 실패한 뒤, 가져간 구독을 다시 대기열에 돌려놓는다. */
    @Modifying
    @Transactional
    @Query("update WaitlistSubscription s set s.notifiedAt = null where s.id = :id")
    int release(@Param("id") Long id);
}
