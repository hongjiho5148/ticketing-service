package com.ticketing.orderservice.transfer.dto;

import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.transfer.TicketTransfer;
import com.ticketing.orderservice.transfer.TransferStatus;
import java.time.LocalDateTime;

public record TransferResponse(
        Long transferId,
        Long orderId,
        // INCOMING: sent to the caller, OUTGOING: sent by the caller.
        String direction,
        // PENDING is reported as EXPIRED once the window has closed, even before anyone answers.
        TransferStatus status,
        // The sender's name for incoming transfers, the masked recipient email for outgoing ones.
        String counterpart,
        String eventTitle,
        String venue,
        LocalDateTime eventStartAt,
        String grade,
        String section,
        String seatNo,
        LocalDateTime createdAt) {

    public static TransferResponse from(Long viewerId, TicketTransfer transfer, TransferStatus effectiveStatus, SeatDetailResponse seat) {
        boolean incoming = transfer.getToUserId().equals(viewerId);
        return new TransferResponse(
                transfer.getId(),
                transfer.getOrderId(),
                incoming ? "INCOMING" : "OUTGOING",
                effectiveStatus,
                incoming ? transfer.getFromName() : transfer.getToEmailMasked(),
                seat.eventTitle(),
                seat.venue(),
                seat.eventStartAt(),
                seat.grade(),
                seat.section(),
                seat.seatNo(),
                transfer.getCreatedAt());
    }
}
