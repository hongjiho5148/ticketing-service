package com.ticketing.orderservice.ticket;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.ticket.dto.ScanResponse;
import com.ticketing.orderservice.ticket.dto.TicketResponse;
import io.jsonwebtoken.JwtException;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketService {

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final QrTokenProvider qrTokenProvider;

    public TicketService(OrderRepository orderRepository, TicketRepository ticketRepository, QrTokenProvider qrTokenProvider) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.qrTokenProvider = qrTokenProvider;
    }

    @Transactional(readOnly = true)
    public TicketResponse getTicket(Long userId, Long orderId) {
        Orders order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        Ticket ticket = ticketRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_ISSUED_YET));

        String qrToken = qrTokenProvider.issueToken(ticket.getId(), ticket.getTokenJti(), ticket.getIssuedAt());
        return new TicketResponse(ticket.getId(), qrToken, ticket.getStatus(), ticket.getIssuedAt());
    }

    public ScanResponse scan(String token) {
        String jti;
        try {
            jti = qrTokenProvider.getJti(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.TICKET_INVALID);
        }

        Ticket ticket = ticketRepository.findByTokenJti(jti).orElseThrow(() -> new ApiException(ErrorCode.TICKET_INVALID));

        LocalDateTime usedAt = LocalDateTime.now();
        int updated = ticketRepository.markUsedIfIssued(jti, usedAt);
        if (updated == 0) {
            throw new ApiException(ErrorCode.TICKET_ALREADY_USED);
        }

        return new ScanResponse(ticket.getId(), ticket.getOrder().getId(), ticket.getOrder().getSeatId(), usedAt);
    }
}
