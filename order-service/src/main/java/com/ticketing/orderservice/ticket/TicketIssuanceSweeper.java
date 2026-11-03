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
 * event-service의 SeatHoldExpirySweeper와 같은 크론 스윕 구조다. 조건에 맞는 행을 찾아 하나씩 처리한다.
 * 여기서는 "공연이 발급 구간 안에 시작하고 아직 티켓이 없는 결제 주문"이며, 주기마다 다시 확인한다. 그래서
 * 구간에 들어온 후보는 조건을 만족하는 첫 주기에 발급되고, 이후 주기에서는 건너뛴다(existsByOrderId).
 * "티켓 발급" 메일은 같은 처리 안에서 곧바로 보내며, 그 실패가 발급 자체를 막지는 않는다(그 시점에 티켓은
 * 이미 저장됐고, 로그만 남긴다).
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
            UserInternalResponse user = authServiceClient.getUser(order.holderId());
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
