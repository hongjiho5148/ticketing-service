package com.ticketing.backend.reservationclient.dto;

import java.time.LocalDateTime;

public record ReservationDetailResponse(
        Long reservationId, Long userId, Long seatId, String status, LocalDateTime holdExpireAt) {
}
