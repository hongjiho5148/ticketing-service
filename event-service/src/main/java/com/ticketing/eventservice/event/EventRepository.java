package com.ticketing.eventservice.event;

import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    /** Every filter is optional (null = don't filter on it); startAt is matched as {@code from <= startAt < to}. */
    @Query("select e from Event e where (:status is null or e.status = :status) "
            + "and (:category is null or e.category = :category) "
            + "and (:keyword is null or lower(e.title) like lower(concat('%', :keyword, '%'))) "
            + "and (:from is null or e.startAt >= :from) "
            + "and (:to is null or e.startAt < :to)")
    Page<Event> search(
            @Param("status") EventStatus status,
            @Param("category") EventCategory category,
            @Param("keyword") String keyword,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
