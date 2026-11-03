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

    // "tickets"(복수, 경로 변수 없음)는 아래의 "/{orderId}/ticket"과 겹치지 않는다. Spring은 문자열 접두사만이
    // 아니라 세그먼트 수로도 구분해서 맞춘다.
    @GetMapping("/tickets")
    public List<TicketHistoryResponse> listMyTickets() {
        return ticketService.listMyTickets(SecurityUtil.getCurrentUserId());
    }

    @GetMapping("/{orderId}/ticket")
    public TicketResponse getTicket(@PathVariable Long orderId) {
        return ticketService.getTicket(SecurityUtil.getCurrentUserId(), orderId);
    }
}
