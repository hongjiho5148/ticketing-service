package com.ticketing.eventservice.kopis;

import com.ticketing.eventservice.common.ApiException;
import com.ticketing.eventservice.common.ErrorCode;
import com.ticketing.eventservice.event.Event;
import com.ticketing.eventservice.event.EventCategory;
import com.ticketing.eventservice.event.EventRepository;
import com.ticketing.eventservice.event.EventStatus;
import com.ticketing.eventservice.kopis.KopisPricing.GradePrice;
import com.ticketing.eventservice.kopis.dto.KopisImportRequest;
import com.ticketing.eventservice.kopis.dto.KopisImportResponse;
import com.ticketing.eventservice.seat.Seat;
import com.ticketing.eventservice.seat.SeatRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings real performance metadata (title, venue, period, genre, cast, synopsis, price points) in from KOPIS.
 * KOPIS has no seating or sales data, so each imported show gets a seat map generated from its price points -
 * this is a ticketing simulation on top of public data, not a resale of real inventory.
 */
@Service
public class KopisImportService {

    private static final Logger log = LoggerFactory.getLogger(KopisImportService.class);

    static final String SOURCE_NOTE =
            "※ 공연 정보는 공연예술통합전산망(KOPIS) 공개 데이터예요. 좌석 배치와 판매는 데모용으로 구성된 것이며 실제 예매가 아니에요.";
    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_WINDOW_DAYS = 90;
    private static final int MAX_PAGES = 3;
    private static final LocalTime DEFAULT_SHOW_TIME = LocalTime.of(19, 0);
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final List<GradePrice> DEMO_PRICES = List.of(new GradePrice("R", 55_000), new GradePrice("S", 33_000));

    private final KopisClient client;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TransactionTemplate transactionTemplate;

    public KopisImportService(
            KopisClient client,
            EventRepository eventRepository,
            SeatRepository seatRepository,
            PlatformTransactionManager transactionManager) {
        this.client = client;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public KopisImportResponse importPerformances(KopisImportRequest request) {
        if (!client.isConfigured()) {
            throw new ApiException(ErrorCode.KOPIS_NOT_CONFIGURED);
        }
        LocalDateTime now = LocalDateTime.now();
        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        LocalDate from = request.from() == null ? now.toLocalDate() : request.from();
        LocalDate to = request.to() == null ? from.plusDays(DEFAULT_WINDOW_DAYS) : request.to();
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.INVALID_INPUT);
        }

        int created = 0;
        int alreadyImported = 0;
        int notUpcoming = 0;
        int failed = 0;
        List<String> createdTitles = new ArrayList<>();

        try {
            for (int page = 1; page <= MAX_PAGES && created < limit; page++) {
                List<KopisPerformance> listed = client.fetchList(request.genre(), from, to, page, Math.min(limit * 3, 100));
                if (listed.isEmpty()) {
                    break;
                }
                for (KopisPerformance summary : listed) {
                    if (created >= limit) {
                        break;
                    }
                    if (eventRepository.existsByExternalId(summary.id())) {
                        alreadyImported++;
                        continue;
                    }
                    try {
                        Optional<Plan> plan = plan(client.fetchDetail(summary.id()), now);
                        if (plan.isEmpty()) {
                            notUpcoming++;
                            continue;
                        }
                        Plan toSave = plan.get();
                        transactionTemplate.executeWithoutResult(status -> save(toSave));
                        created++;
                        createdTitles.add(toSave.event().getTitle());
                    } catch (KopisException e) {
                        failed++;
                        log.warn("KOPIS 공연 가져오기 실패 ({}): {}", summary.id(), e.getMessage());
                    }
                }
            }
        } catch (KopisException e) {
            log.warn("KOPIS 목록 조회 실패: {}", e.getMessage());
            throw new ApiException(ErrorCode.KOPIS_UNAVAILABLE);
        }
        return new KopisImportResponse(created, alreadyImported, notUpcoming, failed, createdTitles);
    }

    private void save(Plan plan) {
        Event saved = eventRepository.save(plan.event());
        seatRepository.saveAll(seatsFor(saved, plan.grades()));
    }

    /** What to persist for one performance: the event itself and the grade/price tiers its seat map is built from. */
    record Plan(Event event, List<GradePrice> grades) {
    }

    /** One block of seats per grade; sizes are fixed because KOPIS says nothing about the venue's capacity. */
    static List<Seat> seatsFor(Event event, List<GradePrice> grades) {
        List<Seat> seats = new ArrayList<>();
        for (GradePrice grade : grades) {
            switch (grade.grade()) {
                case "VIP" -> addBlock(seats, event, grade, "1층 A구역", 4, 10);
                case "R" -> addBlock(seats, event, grade, "1층 B구역", 6, 12);
                default -> {
                    addBlock(seats, event, grade, "2층 C구역", 8, 14);
                    addBlock(seats, event, grade, "2층 D구역", 8, 14);
                }
            }
        }
        return seats;
    }

    private static void addBlock(List<Seat> seats, Event event, GradePrice grade, String section, int rows, int perRow) {
        for (int row = 1; row <= rows; row++) {
            for (int number = 1; number <= perRow; number++) {
                seats.add(new Seat(event, grade.grade(), section, row, number, grade.price()));
            }
        }
    }

    /**
     * Maps one KOPIS performance onto an on-sale event. Empty when the run is already over: the event must start
     * in the future, so a show that is mid-run is listed from tomorrow on.
     */
    static Optional<Plan> plan(KopisPerformance performance, LocalDateTime now) {
        if (performance.from() == null || performance.to() == null) {
            return Optional.empty();
        }
        LocalDate tomorrow = now.toLocalDate().plusDays(1);
        LocalDate firstDay = performance.from().isAfter(tomorrow) ? performance.from() : tomorrow;
        if (firstDay.isAfter(performance.to())) {
            return Optional.empty();
        }

        List<GradePrice> grades = KopisPricing.toGrades(KopisPricing.parsePrices(performance.priceGuidance()));
        boolean demoPrices = grades.isEmpty();

        Event event = new Event(
                truncate(performance.title(), 200),
                truncate(performance.venue() == null ? "공연장 미정" : performance.venue(), 200),
                describe(performance, demoPrices),
                categoryOf(performance.genreName()),
                firstDay.atTime(showTime(performance.scheduleGuidance())),
                now.truncatedTo(ChronoUnit.MINUTES).minusMinutes(1),
                EventStatus.OPEN);
        event.importedFrom(performance.id());
        return Optional.of(new Plan(event, demoPrices ? DEMO_PRICES : grades));
    }

    static EventCategory categoryOf(String genreName) {
        if (genreName == null) {
            return EventCategory.ETC;
        }
        if (genreName.contains("연극")) {
            return EventCategory.PLAY;
        }
        if (genreName.contains("뮤지컬")) {
            return EventCategory.MUSICAL;
        }
        if (genreName.contains("클래식") || genreName.contains("서양음악") || genreName.contains("오페라")) {
            return EventCategory.CLASSIC;
        }
        if (genreName.contains("대중음악")) {
            return EventCategory.CONCERT;
        }
        return EventCategory.ETC;
    }

    /** The first "HH:mm" in the schedule text ("화~금(20:00), 토(15:00,19:00)"), else a 19:00 default. */
    static LocalTime showTime(String scheduleGuidance) {
        if (scheduleGuidance != null) {
            Matcher matcher = TIME.matcher(scheduleGuidance);
            if (matcher.find()) {
                int hour = Integer.parseInt(matcher.group(1));
                int minute = Integer.parseInt(matcher.group(2));
                if (hour < 24 && minute < 60) {
                    return LocalTime.of(hour, minute);
                }
            }
        }
        return DEFAULT_SHOW_TIME;
    }

    static String describe(KopisPerformance p, boolean demoPrices) {
        StringBuilder text = new StringBuilder();
        if (p.synopsis() != null) {
            text.append(truncate(p.synopsis(), 1500)).append("\n\n");
        }
        appendLine(text, "공연 기간", p.from() + " ~ " + p.to());
        appendLine(text, "출연", p.cast());
        appendLine(text, "관람 연령", p.age());
        appendLine(text, "러닝타임", p.runtime());
        appendLine(text, "공연 시간", p.scheduleGuidance());
        appendLine(text, "원본 가격 안내", p.priceGuidance());
        if (demoPrices) {
            text.append("가격 정보가 없어 데모 가격으로 구성했어요.\n");
        }
        text.append("\n").append(SOURCE_NOTE);
        return text.toString();
    }

    private static void appendLine(StringBuilder text, String label, String value) {
        if (value != null) {
            text.append(label).append(": ").append(value).append("\n");
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
