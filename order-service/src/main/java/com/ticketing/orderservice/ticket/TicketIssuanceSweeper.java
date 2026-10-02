package com.ticketing.orderservice.ticket;

import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same cron-sweep shape as event-service's SeatHoldExpirySweeper: find rows matching a condition,
 * act on each. Here that's "paid orders whose show starts within the issuance window and don't
 * have a ticket yet" - re-checked every tick, so a candidate that enters the window gets issued on
 * the first tick it qualifies and is then skipped (existsByOrderId) on every later tick.
 */
@Component
public class TicketIssuanceSweeper {

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final long issuanceLeadHours;

    public TicketIssuanceSweeper(
            OrderRepository orderRepository,
            TicketRepository ticketRepository,
            @Value("${ticket.issuance-lead-hours}") long issuanceLeadHours) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
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
        }
    }
}
