package com.ticketing.reservationservice.reservation;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByStatusAndHoldExpireAtBefore(ReservationStatus status, LocalDateTime holdExpireAt);

    /**
     * 한 공연에서 이 사용자가 지금 가진 좌석이다. 결제된 것과 아직 끝나지 않은 홀드다. 만료 시각이 지난 홀드도
     * ReservationExpirySweeper의 다음 주기까지는 상태가 HOLDING이므로, 상태만 믿지 않고 여기서 만료를 확인한다.
     */
    @Query("select count(r) from Reservation r where r.userId = :userId and r.eventId = :eventId "
            + "and (r.status = com.ticketing.reservationservice.reservation.ReservationStatus.CONFIRMED "
            + "or (r.status = com.ticketing.reservationservice.reservation.ReservationStatus.HOLDING "
            + "and r.holdExpireAt > :now))")
    long countActiveSeats(
            @Param("userId") Long userId, @Param("eventId") Long eventId, @Param("now") LocalDateTime now);
}
