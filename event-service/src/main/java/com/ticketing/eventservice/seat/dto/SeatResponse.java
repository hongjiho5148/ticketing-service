package com.ticketing.eventservice.seat.dto;

import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatStatus;

public record SeatResponse(
        Long id,
        String seatNo,
        String grade,
        String section,
        Integer rowNo,
        Integer seatNumber,
        Integer price,
        SeatStatus status) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSeatNo(),
                seat.getSeatGrade(),
                seat.getSection(),
                seat.getRowNo(),
                seat.getSeatNumber(),
                seat.getPrice(),
                seat.getStatus());
    }
}
