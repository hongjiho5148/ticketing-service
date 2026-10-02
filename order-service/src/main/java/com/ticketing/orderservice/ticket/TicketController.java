package com.ticketing.orderservice.ticket;

import com.ticketing.orderservice.auth.SecurityUtil;
import com.ticketing.orderservice.ticket.dto.TicketResponse;
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

    @GetMapping("/{orderId}/ticket")
    public TicketResponse getTicket(@PathVariable Long orderId) {
        return ticketService.getTicket(SecurityUtil.getCurrentUserId(), orderId);
    }
}
