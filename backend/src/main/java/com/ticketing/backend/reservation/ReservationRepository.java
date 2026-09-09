package com.ticketing.backend.reservation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findBySeatIdAndStatus(Long seatId, ReservationStatus status);
}
