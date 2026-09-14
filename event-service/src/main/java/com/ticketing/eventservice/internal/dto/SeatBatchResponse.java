package com.ticketing.eventservice.internal.dto;

import java.util.List;

public record SeatBatchResponse(List<SeatDetailResponse> seats) {
}
