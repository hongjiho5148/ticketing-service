package com.ticketing.reservationservice.reservation;

import com.ticketing.reservationservice.eventclient.EventServiceClient;
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
                eventServiceClient.release(reservation.getSeatId(), reservation.getHoldExpireAt());
            } catch (Exception e) {
                // event-service도 Seat.holdExpireAt을 기준으로 같은 만료 스윕을 독립적으로 돌리므로,
                // 여기서 해제에 실패해도 마지막 방어선이 아니다. 로그만 남기고 넘어간다.
                log.warn("Failed to release seat {} for expired reservation {}: {}",
                        reservation.getSeatId(), reservation.getId(), e.getMessage());
            }
        }
    }
}
