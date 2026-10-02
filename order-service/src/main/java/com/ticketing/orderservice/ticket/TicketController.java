package com.ticketing.orderservice.ticket;

import com.ticketing.orderservice.auth.SecurityUtil;
import com.ticketing.orderservice.ticket.dto.TicketHistoryResponse;
import com.ticketing.orderservice.ticket.dto.TicketResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    // "tickets" (plural, no path variable) never collides with "/{orderId}/ticket" below - Spring
    // matches them by segment count, not just literal prefix.
    @GetMapping("/tickets")
    public List<TicketHistoryResponse> listMyTickets() {
        return ticketService.listMyTickets(SecurityUtil.getCurrentUserId());
    }

    @GetMapping("/{orderId}/ticket")
    public TicketResponse getTicket(@PathVariable Long orderId) {
        return ticketService.getTicket(SecurityUtil.getCurrentUserId(), orderId);
    }
}
