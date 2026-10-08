package com.ticketing.orderservice.admin.dto;

import java.util.List;

public record AdminOrderListResponse(List<AdminOrderResponse> content, long totalElements) {
}
