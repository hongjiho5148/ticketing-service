package com.ticketing.backend.seat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdAndSeatGrade(Long eventId, String seatGrade);

    List<Seat> findByEventId(Long eventId);
}
