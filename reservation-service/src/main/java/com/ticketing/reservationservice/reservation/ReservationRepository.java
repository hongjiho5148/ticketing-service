package com.ticketing.reservationservice.reservation;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByStatusAndHoldExpireAtBefore(ReservationStatus status, LocalDateTime holdExpireAt);
}
