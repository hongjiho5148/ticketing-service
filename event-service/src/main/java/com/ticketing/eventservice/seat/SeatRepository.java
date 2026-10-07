package com.ticketing.eventservice.seat;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Rows of [eventId, grade, status, seatCount, priceSum] - the admin dashboard folds these into per-event stats. */
    @Query("select s.event.id, s.seatGrade, s.status, count(s), sum(s.price) from Seat s "
            + "group by s.event.id, s.seatGrade, s.status")
    List<Object[]> aggregateStats();

    List<Seat> findByEventIdAndSeatGrade(Long eventId, String seatGrade);

    List<Seat> findByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    List<Seat> findByStatusAndHoldExpireAtBefore(SeatStatus status, LocalDateTime before);
}
