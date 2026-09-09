package com.ticketing.backend.seat.dto;

import com.ticketing.backend.seat.Seat;
import com.ticketing.backend.seat.SeatStatus;

public record SeatResponse(Long id, String seatNo, String grade, Integer price, SeatStatus status) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.getId(), seat.getSeatNo(), seat.getSeatGrade(), seat.getPrice(), seat.getStatus());
    }
}
