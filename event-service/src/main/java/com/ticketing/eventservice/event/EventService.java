package com.ticketing.eventservice.event;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.dto.EventDetailResponse;
import com.ticketing.eventservice.event.dto.EventListResponse;
import com.ticketing.eventservice.event.dto.EventSummaryResponse;
import com.ticketing.eventservice.event.dto.SeatGradeSummary;
import com.ticketing.eventservice.event.dto.SeatSectionSummary;
import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatRepository;
import com.ticketing.eventservice.seat.SeatStatus;
import com.ticketing.eventservice.seat.dto.SeatResponse;
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

    public EventService(EventRepository eventRepository, SeatRepository seatRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
    }

    public EventListResponse listEvents(EventStatus status, Pageable pageable) {
        Page<Event> page = status == null
                ? eventRepository.findAll(pageable)
                : eventRepository.findByStatus(status, pageable);
        List<EventSummaryResponse> content = page.getContent().stream()
                .map(EventSummaryResponse::from)
                .toList();
        return new EventListResponse(content, page.getTotalElements());
    }

    public EventDetailResponse getEventDetail(Long eventId) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
        List<Seat> seats = seatRepository.findByEventId(eventId);

        Map<String, List<Seat>> byGrade = seats.stream().collect(Collectors.groupingBy(Seat::getSeatGrade));
        List<SeatGradeSummary> seatSummary = byGrade.entrySet().stream()
                .map(entry -> new SeatGradeSummary(
                        entry.getKey(),
                        entry.getValue().size(),
                        entry.getValue().stream().filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE).count()))
                .sorted(Comparator.comparing(SeatGradeSummary::grade))
                .toList();

        Map<String, List<Seat>> bySection = seats.stream().collect(Collectors.groupingBy(Seat::getSection));
        List<SeatSectionSummary> sectionSummary = bySection.entrySet().stream()
                .map(entry -> {
                    List<Seat> sectionSeats = entry.getValue();
                    return new SeatSectionSummary(
                            entry.getKey(),
                            sectionSeats.get(0).getSeatGrade(),
                            sectionSeats.get(0).getPrice(),
                            sectionSeats.size(),
                            sectionSeats.stream().filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE).count());
                })
                .sorted(Comparator.comparing(SeatSectionSummary::section))
                .toList();

        return EventDetailResponse.of(event, seatSummary, sectionSummary);
    }

    public List<SeatResponse> listSeats(Long eventId, String grade) {
        if (!eventRepository.existsById(eventId)) {
            throw new ApiException(ErrorCode.EVENT_NOT_FOUND);
        }
        List<Seat> seats = grade == null
                ? seatRepository.findByEventId(eventId)
                : seatRepository.findByEventIdAndSeatGrade(eventId, grade);
        return seats.stream().map(SeatResponse::from).toList();
    }
}
