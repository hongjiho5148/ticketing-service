package com.ticketing.reservationservice.eventclient.dto;

import java.time.LocalDateTime;

/** event-service 내부 SeatDetailResponse를 그대로 본뜬 것이다. 공연 요약이 들어 있어서 대부분의 호출이 한 번의 왕복으로 끝난다. */
public record SeatDetailResponse(
        Long seatId,
        Long eventId,
        String eventTitle,
        String venue,
        LocalDateTime eventStartAt,
        boolean salesClosed,
        String grade,
        String section,
        Integer rowNo,
        Integer seatNumber,
        String seatNo,
        Integer price,
        String status) {
}
