package com.ticketing.backend.eventclient.dto;

import java.util.List;

public record SeatBatchRequest(List<Long> seatIds) {
}
