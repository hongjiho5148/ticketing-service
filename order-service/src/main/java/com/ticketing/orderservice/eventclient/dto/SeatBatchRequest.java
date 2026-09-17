package com.ticketing.orderservice.eventclient.dto;

import java.util.List;

public record SeatBatchRequest(List<Long> seatIds) {
}
