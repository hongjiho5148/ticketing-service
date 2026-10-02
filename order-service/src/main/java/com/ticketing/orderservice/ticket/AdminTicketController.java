package com.ticketing.orderservice.ticket;

import com.ticketing.orderservice.ticket.dto.ScanRequest;
import com.ticketing.orderservice.ticket.dto.ScanResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff-facing scan endpoint - gated to ROLE_ADMIN in SecurityConfig, not by anything in here. */
@RestController
@RequestMapping("/api/admin/tickets")
public class AdminTicketController {

    private final TicketService ticketService;

    public AdminTicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/scan")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return ticketService.scan(request.token());
    }
}
