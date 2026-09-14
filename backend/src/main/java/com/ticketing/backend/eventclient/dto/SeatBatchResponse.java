package com.ticketing.backend.eventclient.dto;

import java.util.List;

public record SeatBatchResponse(List<SeatDetailResponse> seats) {
}
