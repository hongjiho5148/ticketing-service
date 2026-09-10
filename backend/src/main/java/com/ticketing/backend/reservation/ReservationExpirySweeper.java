package com.ticketing.backend.reservation;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReservationExpirySweeper {

    private final ReservationRepository reservationRepository;

    public ReservationExpirySweeper(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    @Scheduled(cron = "${reservation.hold-expire-sweep-cron}")
    @Transactional
    public void releaseExpiredHolds() {
        List<Reservation> expired =
                reservationRepository.findByStatusAndHoldExpireAtBefore(ReservationStatus.HOLDING, LocalDateTime.now());
        for (Reservation reservation : expired) {
            reservation.expire();
            reservation.getSeat().release();
        }
    }
}
