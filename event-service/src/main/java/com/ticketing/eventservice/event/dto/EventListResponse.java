package com.ticketing.eventservice.event.dto;

import java.util.List;

public record EventListResponse(List<EventSummaryResponse> content, long totalElements) {
}
