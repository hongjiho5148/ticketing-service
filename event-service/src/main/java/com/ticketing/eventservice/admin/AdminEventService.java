package com.ticketing.eventservice.admin;

import com.ticketing.eventservice.admin.dto.EventStatsResponse;
import com.ticketing.eventservice.admin.dto.EventUpsertRequest;
import com.ticketing.eventservice.admin.dto.SeatBlockRequest;
import com.ticketing.eventservice.admin.dto.SeatBulkCreateRequest;
import com.ticketing.eventservice.admin.dto.SeatBulkCreateResponse;
import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.dto.EventSummaryResponse;
import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatRepository;
import com.ticketing.eventservice.seat.SeatStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AdminEventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    public AdminEventService(EventRepository eventRepository, SeatRepository seatRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
    }

    public EventSummaryResponse createEvent(EventUpsertRequest request) {
        validateTimes(request);
        Event event = eventRepository.save(new Event(
                request.title(),
                request.venue(),
                request.description(),
                request.category(),
                request.startAt(),
                request.openAt(),
                request.status()));
        return EventSummaryResponse.from(event);
    }

    public EventSummaryResponse updateEvent(Long eventId, EventUpsertRequest request) {
        validateTimes(request);
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
        event.update(
                request.title(),
                request.venue(),
                request.description(),
                request.category(),
                request.startAt(),
                request.openAt(),
                request.status());
        return EventSummaryResponse.from(event);
    }

    public SeatBulkCreateResponse createSeats(Long eventId, SeatBulkCreateRequest request) {
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));

        Set<String> usedSections = new HashSet<>();
        seatRepository.findByEventId(eventId).forEach(seat -> usedSections.add(seat.getSection()));
        for (SeatBlockRequest block : request.blocks()) {
            if (!usedSections.add(block.section())) {
                throw new ApiException(ErrorCode.SECTION_ALREADY_EXISTS);
            }
        }

        List<Seat> seats = new ArrayList<>();
        for (SeatBlockRequest block : request.blocks()) {
            for (int row = 1; row <= block.rows(); row++) {
                for (int number = 1; number <= block.seatsPerRow(); number++) {
                    seats.add(new Seat(event, block.grade(), block.section(), row, number, block.price()));
                }
            }
        }
        seatRepository.saveAll(seats);
        return new SeatBulkCreateResponse(seats.size());
    }

    public void updateSeatPrice(Long seatId, Integer price) {
        Seat seat = seatRepository.findById(seatId).orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_FOUND));
        if (seat.getStatus() == SeatStatus.SOLD) {
            throw new ApiException(ErrorCode.SEAT_NOT_MODIFIABLE);
        }
        seat.changePrice(price);
    }

    @Transactional(readOnly = true)
    public List<EventStatsResponse> stats() {
        // eventId -> grade -> counts indexed by SeatStatus ordinal (AVAILABLE, HOLD, SOLD)
        Map<Long, Map<String, long[]>> byEvent = new LinkedHashMap<>();
        Map<Long, Long> soldRevenue = new LinkedHashMap<>();
        for (Object[] row : seatRepository.aggregateStats()) {
            Long eventId = (Long) row[0];
            String grade = (String) row[1];
            SeatStatus status = (SeatStatus) row[2];
            long count = (Long) row[3];
            long revenue = row[4] == null ? 0 : ((Number) row[4]).longValue();
            long[] counts = byEvent
                    .computeIfAbsent(eventId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(grade, k -> new long[3]);
            counts[status.ordinal()] += count;
            if (status == SeatStatus.SOLD) {
                soldRevenue.merge(eventId, revenue, Long::sum);
            }
        }

        List<EventStatsResponse> result = new ArrayList<>();
        for (Event event : eventRepository.findAll()) {
            Map<String, long[]> grades = byEvent.getOrDefault(event.getId(), Map.of());
            long available = 0;
            long hold = 0;
            long sold = 0;
            List<EventStatsResponse.GradeStats> gradeStats = new ArrayList<>();
            for (Map.Entry<String, long[]> entry : grades.entrySet()) {
                long[] c = entry.getValue();
                long gradeAvailable = c[SeatStatus.AVAILABLE.ordinal()];
                long gradeHold = c[SeatStatus.HOLD.ordinal()];
                long gradeSold = c[SeatStatus.SOLD.ordinal()];
                available += gradeAvailable;
                hold += gradeHold;
                sold += gradeSold;
                gradeStats.add(new EventStatsResponse.GradeStats(
                        entry.getKey(), gradeAvailable + gradeHold + gradeSold, gradeAvailable, gradeHold, gradeSold));
            }
            result.add(new EventStatsResponse(
                    event.getId(),
                    event.getTitle(),
                    available + hold + sold,
                    available,
                    hold,
                    sold,
                    soldRevenue.getOrDefault(event.getId(), 0L),
                    gradeStats));
        }
        return result;
    }

    private void validateTimes(EventUpsertRequest request) {
        if (!request.openAt().isBefore(request.startAt())) {
            throw new ApiException(ErrorCode.INVALID_INPUT);
        }
    }
}
