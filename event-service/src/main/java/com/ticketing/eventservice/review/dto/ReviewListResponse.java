package com.ticketing.eventservice.review.dto;

import java.util.List;

public record ReviewListResponse(List<ReviewResponse> content, long totalElements, Double averageRating) {
}
