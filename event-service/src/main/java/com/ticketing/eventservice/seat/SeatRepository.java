package com.ticketing.eventservice.seat;

import com.ticketing.eventservice.seat.dto.SeatResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Rows of [eventId, grade, status, seatCount, priceSum] - the admin dashboard folds these into per-event stats. */
    @Query("select s.event.id, s.seatGrade, s.status, count(s), sum(s.price) from Seat s "
            + "group by s.event.id, s.seatGrade, s.status")
    List<Object[]> aggregateStats();

    /**
     * The seat list the seat map draws from, read straight into the response record: no Seat entities get built,
     * tracked by the persistence context or dirty-checked, which for a few thousand seats per request is most of the
     * cost. Ordered by id so the response is stable (it used to be whatever order the index happened to return).
     */
    @Query("select new com.ticketing.eventservice.seat.dto.SeatResponse(s.id, s.seatNo, s.seatGrade, s.section, "
            + "s.rowNo, s.seatNumber, s.price, s.status) from Seat s where s.event.id = :eventId order by s.id")
    List<SeatResponse> listForEvent(@Param("eventId") Long eventId);

    @Query("select new com.ticketing.eventservice.seat.dto.SeatResponse(s.id, s.seatNo, s.seatGrade, s.section, "
            + "s.rowNo, s.seatNumber, s.price, s.status) from Seat s "
            + "where s.event.id = :eventId and s.seatGrade = :grade order by s.id")
    List<SeatResponse> listForEventAndGrade(@Param("eventId") Long eventId, @Param("grade") String grade);

    /** Rows of [grade, seatCount, availableCount] for one event - what the detail page needs, computed by the database. */
    @Query("select s.seatGrade, count(s), sum(case when s.status = com.ticketing.eventservice.seat.SeatStatus.AVAILABLE "
            + "then 1 else 0 end) from Seat s where s.event.id = :eventId group by s.seatGrade")
    List<Object[]> gradeSummary(@Param("eventId") Long eventId);

    /** Rows of [section, grade, price, seatCount, availableCount] for one event (a section has one grade and price). */
    @Query("select s.section, min(s.seatGrade), min(s.price), count(s), "
            + "sum(case when s.status = com.ticketing.eventservice.seat.SeatStatus.AVAILABLE then 1 else 0 end) "
            + "from Seat s where s.event.id = :eventId group by s.section")
    List<Object[]> sectionSummary(@Param("eventId") Long eventId);

    List<Seat> findByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    List<Seat> findByStatusAndHoldExpireAtBefore(SeatStatus status, LocalDateTime before);
}
