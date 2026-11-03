package com.ticketing.orderservice.transfer.dto;

import com.ticketing.orderservice.eventclient.dto.SeatDetailResponse;
import com.ticketing.orderservice.transfer.TicketTransfer;
import com.ticketing.orderservice.transfer.TransferStatus;
import java.time.LocalDateTime;

public record TransferResponse(
        Long transferId,
        Long orderId,
        // INCOMING: 호출자가 받은 것, OUTGOING: 호출자가 보낸 것.
        String direction,
        // 창이 닫히면 아무도 응답하지 않았어도 PENDING은 EXPIRED로 보고한다.
        TransferStatus status,
        // 받은 양도는 보낸 사람의 이름, 보낸 양도는 마스킹한 받는 사람의 이메일이다.
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
