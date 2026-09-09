package com.ticketing.backend.event.dto;

import java.util.List;

public record EventListResponse(List<EventSummaryResponse> content, long totalElements) {
}
