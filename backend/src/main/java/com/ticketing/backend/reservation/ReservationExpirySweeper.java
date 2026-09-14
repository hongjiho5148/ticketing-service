package com.ticketing.backend.reservation;

import com.ticketing.backend.eventclient.EventServiceClient;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final ReservationRepository reservationRepository;
    private final EventServiceClient eventServiceClient;

    public ReservationExpirySweeper(ReservationRepository reservationRepository, EventServiceClient eventServiceClient) {
        this.reservationRepository = reservationRepository;
        this.eventServiceClient = eventServiceClient;
    }

    @Scheduled(cron = "${reservation.hold-expire-sweep-cron}")
    @Transactional
    public void releaseExpiredHolds() {
        List<Reservation> expired =
                reservationRepository.findByStatusAndHoldExpireAtBefore(ReservationStatus.HOLDING, LocalDateTime.now());
        for (Reservation reservation : expired) {
            reservation.expire();
            try {
                eventServiceClient.release(reservation.getSeatId());
            } catch (Exception e) {
                // event-service runs the same expiry sweep independently on Seat.holdExpireAt, so
                // a failed release here isn't the last line of defense - just log and move on.
                log.warn("Failed to release seat {} for expired reservation {}: {}",
                        reservation.getSeatId(), reservation.getId(), e.getMessage());
            }
        }
    }
}
