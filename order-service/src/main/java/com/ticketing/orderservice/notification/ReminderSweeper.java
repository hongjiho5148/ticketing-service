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
 * TicketIssuanceSweeper/SeatHoldExpirySweeper와 같은 크론 스윕 구조다. 메일 발송에 실패하면
 * reminderSentAt을 null로 두어서 다음 주기(범위 안이면)에 그냥 다시 시도한다. 발송에 성공했거나
 * 수신 거부가 확인됐을 때만 그 주문을 처리한 것으로 표시한다.
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
