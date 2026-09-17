package com.ticketing.orderservice.order.dto;

import java.util.List;

public record OrderHistoryListResponse(List<OrderHistoryResponse> content) {
}
