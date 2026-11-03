package com.ticketing.eventservice.event;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    boolean existsByExternalId(String externalId);

    List<Event> findByStatusAndOpenAtLessThanEqual(EventStatus status, LocalDateTime openAt);

    /** 아직 닫힘으로 표시되지 않았지만 시작이 주어진 시각 이전이거나 같은 공연들이다. */
    List<Event> findByStatusNotAndStartAtLessThanEqual(EventStatus status, LocalDateTime startAt);

    /** 모든 필터는 선택이다(null = 그 조건으로 거르지 않음). startAt은 {@code from <= startAt < to}로 맞춘다. */
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
