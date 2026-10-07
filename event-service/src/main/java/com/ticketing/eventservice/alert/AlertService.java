package com.ticketing.eventservice.alert;

import com.ticketing.eventservice.alert.dto.AlertStatusResponse;
import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.seat.SeatRepository;
import com.ticketing.eventservice.seat.SeatStatus;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AlertService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final OpenAlertSubscriptionRepository openAlertRepository;
    private final WaitlistSubscriptionRepository waitlistRepository;

    public AlertService(
            EventRepository eventRepository,
            SeatRepository seatRepository,
            OpenAlertSubscriptionRepository openAlertRepository,
            WaitlistSubscriptionRepository waitlistRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.openAlertRepository = openAlertRepository;
        this.waitlistRepository = waitlistRepository;
    }

    @Transactional(readOnly = true)
    public AlertStatusResponse openAlertStatus(Long userId, Long eventId) {
        return new AlertStatusResponse(openAlertRepository.findByUserIdAndEventId(userId, eventId)
                .map(s -> s.getNotifiedAt() == null)
                .orElse(false));
    }

    public AlertStatusResponse subscribeOpenAlert(Long userId, Long eventId) {
        Event event = loadEvent(eventId);
        if (event.getStatus() != EventStatus.UPCOMING || !event.getOpenAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(ErrorCode.OPEN_ALERT_NOT_AVAILABLE);
        }
        openAlertRepository.findByUserIdAndEventId(userId, eventId).ifPresentOrElse(
                OpenAlertSubscription::rearm,
                () -> openAlertRepository.save(new OpenAlertSubscription(userId, eventId)));
        return new AlertStatusResponse(true);
    }

    public void unsubscribeOpenAlert(Long userId, Long eventId) {
        openAlertRepository.deleteByUserIdAndEventId(userId, eventId);
    }

    @Transactional(readOnly = true)
    public AlertStatusResponse waitlistStatus(Long userId, Long eventId) {
        return new AlertStatusResponse(waitlistRepository.findByUserIdAndEventId(userId, eventId)
                .map(s -> s.getNotifiedAt() == null)
                .orElse(false));
    }

    public AlertStatusResponse subscribeWaitlist(Long userId, Long eventId) {
        Event event = loadEvent(eventId);
        // Only a sold-out (or fully held) event has anything to wait for; otherwise just book a seat.
        if (event.getStatus() != EventStatus.OPEN
                || seatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE) > 0) {
            throw new ApiException(ErrorCode.WAITLIST_NOT_AVAILABLE);
        }
        waitlistRepository.findByUserIdAndEventId(userId, eventId).ifPresentOrElse(
                WaitlistSubscription::rearm,
                () -> waitlistRepository.save(new WaitlistSubscription(userId, eventId)));
        return new AlertStatusResponse(true);
    }

    public void unsubscribeWaitlist(Long userId, Long eventId) {
        waitlistRepository.deleteByUserIdAndEventId(userId, eventId);
    }

    private Event loadEvent(Long eventId) {
        return eventRepository.findById(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
    }
}
