package com.ticketing.eventservice.review.dto;

import com.ticketing.eventservice.review.Review;
import java.time.LocalDateTime;

public record ReviewResponse(Long id, Integer rating, String content, LocalDateTime createdAt) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(review.getId(), review.getRating(), review.getContent(), review.getCreatedAt());
    }
}
