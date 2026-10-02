package com.ticketing.orderservice.ticket;

import com.ticketing.orderservice.common.ApiException;
import com.ticketing.orderservice.common.ErrorCode;
import com.ticketing.orderservice.eventclient.EventServiceClient;
import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.order.OrderRepository;
import com.ticketing.orderservice.order.Orders;
import com.ticketing.orderservice.ticket.dto.ScanResponse;
import com.ticketing.orderservice.ticket.dto.TicketHistoryResponse;
import com.ticketing.orderservice.ticket.dto.TicketResponse;
import io.jsonwebtoken.JwtException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketService {

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final QrTokenProvider qrTokenProvider;
    private final EventServiceClient eventServiceClient;

    public TicketService(
            OrderRepository orderRepository,
            TicketRepository ticketRepository,
            QrTokenProvider qrTokenProvider,
            EventServiceClient eventServiceClient) {
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.qrTokenProvider = qrTokenProvider;
        this.eventServiceClient = eventServiceClient;
    }

    @Transactional(readOnly = true)
    public List<TicketHistoryResponse> listMyTickets(Long userId) {
        List<Ticket> tickets = ticketRepository.findByOrder_UserIdOrderByIssuedAtDesc(userId);
        List<Long> seatIds = tickets.stream().map(t -> t.getOrder().getSeatId()).distinct().toList();
        Map<Long, SeatDetailResponse> seatsById = seatIds.isEmpty()
                ? Map.of()
                : eventServiceClient.getSeats(seatIds).stream()
                        .collect(Collectors.toMap(SeatDetailResponse::seatId, Function.identity()));
        return tickets.stream()
                .map(ticket -> TicketHistoryResponse.from(ticket, seatsById.get(ticket.getOrder().getSeatId())))
                .toList();
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
