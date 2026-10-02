package com.ticketing.eventservice.review;

import com.ticketing.eventservice.auth.SecurityUtil;
import com.ticketing.eventservice.review.dto.ReviewCreateRequest;
import com.ticketing.eventservice.review.dto.ReviewListResponse;
import com.ticketing.eventservice.review.dto.ReviewResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ReviewListResponse list(
            @PathVariable Long eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reviewService.list(eventId, PageRequest.of(page, size));
    }

    @PostMapping
    public ResponseEntity<ReviewResponse> create(@PathVariable Long eventId, @Valid @RequestBody ReviewCreateRequest request) {
        ReviewResponse response = reviewService.create(SecurityUtil.getCurrentUserId(), eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
