package com.ticketing.eventservice.alert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpenAlertSubscriptionRepository extends JpaRepository<OpenAlertSubscription, Long> {

    Optional<OpenAlertSubscription> findByUserIdAndEventId(Long userId, Long eventId);

    void deleteByUserIdAndEventId(Long userId, Long eventId);

    /** Un-notified subscribers of events whose booking window has already opened. */
    @Query("select s from OpenAlertSubscription s, Event e where s.eventId = e.id "
            + "and s.notifiedAt is null and e.openAt <= :now order by s.id")
    List<OpenAlertSubscription> findDue(@Param("now") LocalDateTime now, Pageable pageable);
}
