package com.ticketing.eventservice.seat;

import com.ticketing.eventservice.alert.SeatsReleasedEvent;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-healing safety net: releases any seat stuck in HOLD past its holdExpireAt, independent of
 * whether the caller (backend's Reservation) ever follows up with a release. Since the hold and
 * the Reservation row now live in different services/DBs, this is what bounds an orphaned hold
 * (e.g. backend crashed right after a successful hold call) to a few minutes instead of forever -
 * backend's own ReservationExpirySweeper does the same release call on its side; both landing on
 * an already-AVAILABLE seat is harmless since release() just unconditionally sets that status.
 * Seats it frees are announced per event so the cancellation-ticket waitlist hears about them.
 */
@Component
public class SeatHoldExpirySweeper {

    private final SeatRepository seatRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SeatHoldExpirySweeper(SeatRepository seatRepository, ApplicationEventPublisher eventPublisher) {
        this.seatRepository = seatRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "${seat.hold-expiry-sweep-cron}")
    @Transactional
    public void releaseExpiredHolds() {
        List<Seat> expired = seatRepository.findByStatusAndHoldExpireAtBefore(SeatStatus.HOLD, LocalDateTime.now());
        Map<Long, Integer> releasedPerEvent = new HashMap<>();
        for (Seat seat : expired) {
            seat.release();
            releasedPerEvent.merge(seat.getEvent().getId(), 1, Integer::sum);
        }
        releasedPerEvent.forEach((eventId, count) -> eventPublisher.publishEvent(new SeatsReleasedEvent(eventId, count)));
    }
}
