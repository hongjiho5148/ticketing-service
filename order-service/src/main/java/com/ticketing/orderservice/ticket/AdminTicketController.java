package com.ticketing.orderservice.ticket;

import com.ticketing.orderservice.ticket.dto.ScanRequest;
import com.ticketing.orderservice.ticket.dto.ScanResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 스태프용 스캔 엔드포인트다. ROLE_ADMIN만 접근할 수 있고, 이 제한은 이 클래스가 아니라 SecurityConfig가 건다. */
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
