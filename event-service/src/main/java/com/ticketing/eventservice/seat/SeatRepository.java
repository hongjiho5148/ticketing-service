package com.ticketing.eventservice.seat;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdAndSeatGrade(Long eventId, String seatGrade);

    List<Seat> findByEventId(Long eventId);

    List<Seat> findByStatusAndHoldExpireAtBefore(SeatStatus status, LocalDateTime before);
}
