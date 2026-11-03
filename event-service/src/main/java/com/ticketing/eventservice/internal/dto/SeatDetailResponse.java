package com.ticketing.eventservice.internal.dto;

import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatStatus;
import java.time.LocalDateTime;

/** 서비스 사이 좌석 상세다. 공연 요약이 들어 있어서 호출하는 쪽(backend)이 두 번째 왕복을 거의 하지 않아도 된다. */
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
        SeatStatus status) {

    public static SeatDetailResponse from(Seat seat) {
        return new SeatDetailResponse(
                seat.getId(),
                seat.getEvent().getId(),
                seat.getEvent().getTitle(),
                seat.getEvent().getVenue(),
                seat.getEvent().getStartAt(),
                seat.getEvent().isSalesClosed(LocalDateTime.now()),
                seat.getSeatGrade(),
                seat.getSection(),
                seat.getRowNo(),
                seat.getSeatNumber(),
                seat.getSeatNo(),
                seat.getPrice(),
                seat.getStatus());
    }
}
