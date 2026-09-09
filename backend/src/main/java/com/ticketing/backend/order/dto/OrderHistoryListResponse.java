package com.ticketing.backend.order.dto;

import java.util.List;

public record OrderHistoryListResponse(List<OrderHistoryResponse> content) {
}
