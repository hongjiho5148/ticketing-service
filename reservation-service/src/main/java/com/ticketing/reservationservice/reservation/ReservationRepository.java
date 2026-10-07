package com.ticketing.reservationservice.reservation;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByStatusAndHoldExpireAtBefore(ReservationStatus status, LocalDateTime holdExpireAt);

    /**
     * Seats this user currently has on one event: paid ones plus holds that haven't lapsed. A hold
     * past its expiry still has status HOLDING until ReservationExpirySweeper's next tick, so the
     * expiry is checked here rather than trusting the status alone.
     */
    @Query("select count(r) from Reservation r where r.userId = :userId and r.eventId = :eventId "
            + "and (r.status = com.ticketing.reservationservice.reservation.ReservationStatus.CONFIRMED "
            + "or (r.status = com.ticketing.reservationservice.reservation.ReservationStatus.HOLDING "
            + "and r.holdExpireAt > :now))")
    long countActiveSeats(
            @Param("userId") Long userId, @Param("eventId") Long eventId, @Param("now") LocalDateTime now);
}
