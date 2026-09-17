package com.ticketing.orderservice.eventclient.dto;

import java.util.List;

public record SeatBatchResponse(List<SeatDetailResponse> seats) {
}
