package com.ticketing.orderservice.ticket;

import com.ticketing.orderservice.authclient.AuthServiceClient;
import com.ticketing.orderservice.authclient.dto.UserInternalResponse;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.notification.MailService;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same cron-sweep shape as event-service's SeatHoldExpirySweeper: find rows matching a condition,
 * act on each. Here that's "paid orders whose show starts within the issuance window and don't
 * have a ticket yet" - re-checked every tick, so a candidate that enters the window gets issued on
 * the first tick it qualifies and is then skipped (existsByOrderId) on every later tick. The
 * "ticket issued" mail is sent right after, in the same pass - a failure there never blocks
 * issuance itself (the ticket is already saved by that point), it's just logged.
 */
@Component
public class TicketIssuanceSweeper {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuanceSweeper.class);

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final AuthServiceClient authServiceClient;
    private final EventServiceClient eventServiceClient;
    private final MailService mailService;
    private final long issuanceLeadHours;

    public TicketIssuanceSweeper(
            OrderRepository orderRepository,
            TicketRepository ticketRepository,
            AuthServiceClient authServiceClient,
            EventServiceClient eventServiceClient,
            MailService mailService,
            @Value("${ticket.issuance-lead-hours}") long issuanceLeadHours) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.authServiceClient = authServiceClient;
        this.eventServiceClient = eventServiceClient;
        this.mailService = mailService;
        this.issuanceLeadHours = issuanceLeadHours;
    }

    @Scheduled(cron = "${ticket.issuance-sweep-cron}")
    @Transactional
    public void issueUpcomingTickets() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusHours(issuanceLeadHours);
        List<Orders> candidates = orderRepository.findByStatusAndEventStartAtBetween(OrderStatus.PAID, now, windowEnd);
        for (Orders order : candidates) {
            if (ticketRepository.existsByOrderId(order.getId())) {
                continue;
            }
            ticketRepository.save(new Ticket(order, UUID.randomUUID().toString()));
            notifyTicketIssued(order);
        }
    }

    private void notifyTicketIssued(Orders order) {
        try {
            UserInternalResponse user = authServiceClient.getUser(order.getUserId());
            if (!user.emailOptIn()) {
                return;
            }
            SeatDetailResponse seat = eventServiceClient.getSeat(order.getSeatId());
            mailService.sendTicketIssuedEmail(user.email(), user.name(), seat.eventTitle());
        } catch (Exception e) {
            log.warn("QR 발급 알림 발송 실패 orderId={}: {}", order.getId(), e.getMessage());
        }
    }
}
