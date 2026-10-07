package com.ticketing.orderservice.notification;

import com.ticketing.orderservice.authclient.AuthServiceClient;
import com.ticketing.orderservice.authclient.dto.UserInternalResponse;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same cron-sweep shape as TicketIssuanceSweeper/SeatHoldExpirySweeper. A mail-send failure here
 * leaves reminderSentAt null so the next tick (within the window) just retries - only a
 * successful send, or a confirmed opt-out, marks the order as handled.
 */
@Component
public class ReminderSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReminderSweeper.class);

    private final OrderRepository orderRepository;
    private final AuthServiceClient authServiceClient;
    private final EventServiceClient eventServiceClient;
    private final MailService mailService;
    private final long leadHours;
    private final long windowHours;

    public ReminderSweeper(
            OrderRepository orderRepository,
            AuthServiceClient authServiceClient,
            EventServiceClient eventServiceClient,
            MailService mailService,
            @Value("${reminder.lead-hours}") long leadHours,
            @Value("${reminder.window-hours}") long windowHours) {
        this.orderRepository = orderRepository;
        this.authServiceClient = authServiceClient;
        this.eventServiceClient = eventServiceClient;
        this.mailService = mailService;
        this.leadHours = leadHours;
        this.windowHours = windowHours;
    }

    @Scheduled(cron = "${reminder.sweep-cron}")
    @Transactional
    public void sendShowReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.plusHours(leadHours);
        LocalDateTime windowEnd = windowStart.plusHours(windowHours);
        List<Orders> candidates = orderRepository.findByStatusAndReminderSentAtIsNullAndEventStartAtBetween(
                OrderStatus.PAID, windowStart, windowEnd);

        for (Orders order : candidates) {
            try {
                UserInternalResponse user = authServiceClient.getUser(order.holderId());
                if (!user.emailOptIn()) {
                    order.markReminderSent();
                    continue;
                }
                SeatDetailResponse seat = eventServiceClient.getSeat(order.getSeatId());
                mailService.sendShowReminderEmail(user.email(), user.name(), seat.eventTitle(), order.getEventStartAt());
                order.markReminderSent();
            } catch (Exception e) {
                log.warn("D-1 리마인더 발송 실패 orderId={}: {}", order.getId(), e.getMessage());
            }
        }
    }
}
