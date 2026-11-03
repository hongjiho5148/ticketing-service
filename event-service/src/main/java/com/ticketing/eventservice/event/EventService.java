package com.ticketing.eventservice.event;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.dto.EventDetailResponse;
import com.ticketing.eventservice.event.dto.EventListResponse;
import com.ticketing.eventservice.event.dto.EventSummaryResponse;
import com.ticketing.eventservice.event.dto.RatingStats;
import com.ticketing.eventservice.event.dto.SeatGradeSummary;
import com.ticketing.eventservice.event.dto.SeatSectionSummary;
import com.ticketing.eventservice.review.ReviewRepository;
import com.ticketing.eventservice.seat.SeatRepository;
import com.ticketing.eventservice.seat.dto.SeatResponse;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final ReviewRepository reviewRepository;

    public EventService(
            EventRepository eventRepository, SeatRepository seatRepository, ReviewRepository reviewRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.reviewRepository = reviewRepository;
    }

    /**
     * @param from 공연 날짜 구간의 시작(포함, 예: 달력의 한 달). null이면 제한 없음
     * @param to 구간의 끝(포함하지 않음). null이면 제한 없음
     */
    public EventListResponse listEvents(
            EventStatus status,
            EventCategory category,
            String keyword,
            LocalDate from,
            LocalDate to,
            Pageable pageable) {
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Page<Event> page = eventRepository.search(
                status,
                category,
                normalizedKeyword,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.atStartOfDay(),
                pageable);

        Map<Long, RatingStats> ratings = ratingsFor(page.getContent());
        List<EventSummaryResponse> content = page.getContent().stream()
                .map(event -> EventSummaryResponse.from(event, ratings.getOrDefault(event.getId(), RatingStats.NONE)))
                .toList();
        return new EventListResponse(content, page.getTotalElements());
    }

    private Map<Long, RatingStats> ratingsFor(List<Event> events) {
        if (events.isEmpty()) {
            return Map.of();
        }
        return reviewRepository.ratingStats(events.stream().map(Event::getId).toList()).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0], row -> new RatingStats(((Number) row[1]).doubleValue(), (Long) row[2])));
    }

    public EventDetailResponse getEventDetail(Long eventId) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
        long reviewCount = reviewRepository.countByEventId(eventId);
        RatingStats rating = reviewCount == 0
                ? RatingStats.NONE
                : new RatingStats(reviewRepository.findAverageRatingByEventId(eventId), reviewCount);

        // 공연의 모든 좌석을 불러와서 세는 대신 데이터베이스가(GROUP BY) 센다.
        List<SeatGradeSummary> seatSummary = seatRepository.gradeSummary(eventId).stream()
                .map(row -> new SeatGradeSummary((String) row[0], ((Number) row[1]).longValue(), ((Number) row[2]).longValue()))
                .sorted(Comparator.comparing(SeatGradeSummary::grade))
                .toList();

        List<SeatSectionSummary> sectionSummary = seatRepository.sectionSummary(eventId).stream()
                .map(row -> new SeatSectionSummary(
                        (String) row[0],
                        (String) row[1],
                        ((Number) row[2]).intValue(),
                        ((Number) row[3]).longValue(),
                        ((Number) row[4]).longValue()))
                .sorted(Comparator.comparing(SeatSectionSummary::section))
                .toList();

        return EventDetailResponse.of(event, rating, seatSummary, sectionSummary);
    }

    public List<SeatResponse> listSeats(Long eventId, String grade) {
        if (!eventRepository.existsById(eventId)) {
            throw new ApiException(ErrorCode.EVENT_NOT_FOUND);
        }
        return grade == null
                ? seatRepository.listForEvent(eventId)
                : seatRepository.listForEventAndGrade(eventId, grade);
    }
}
