package com.ticketing.eventservice.review;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.orderclient.OrderServiceClient;
import com.ticketing.eventservice.review.dto.ReviewCreateRequest;
import com.ticketing.eventservice.review.dto.ReviewListResponse;
import com.ticketing.eventservice.review.dto.ReviewResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final EventRepository eventRepository;
    private final OrderServiceClient orderServiceClient;

    public ReviewService(
            ReviewRepository reviewRepository, EventRepository eventRepository, OrderServiceClient orderServiceClient) {
        this.reviewRepository = reviewRepository;
        this.eventRepository = eventRepository;
        this.orderServiceClient = orderServiceClient;
    }

    @Transactional(readOnly = true)
    public ReviewListResponse list(Long eventId, Pageable pageable) {
        Page<Review> page = reviewRepository.findByEventIdOrderByCreatedAtDesc(eventId, pageable);
        return new ReviewListResponse(
                page.getContent().stream().map(ReviewResponse::from).toList(),
                page.getTotalElements(),
                reviewRepository.findAverageRatingByEventId(eventId));
    }

    public ReviewResponse create(Long userId, Long eventId, ReviewCreateRequest request) {
        if (!eventRepository.existsById(eventId)) {
            throw new ApiException(ErrorCode.EVENT_NOT_FOUND);
        }
        if (reviewRepository.existsByUserIdAndEventId(userId, eventId)) {
            throw new ApiException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }
        if (!orderServiceClient.hasPaidOrder(userId, eventId)) {
            throw new ApiException(ErrorCode.REVIEW_NOT_ELIGIBLE);
        }
        Review review = reviewRepository.save(new Review(userId, eventId, request.rating(), request.content()));
        return ReviewResponse.from(review);
    }
}
