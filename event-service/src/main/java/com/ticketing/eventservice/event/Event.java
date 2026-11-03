package com.ticketing.eventservice.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "event", uniqueConstraints = @UniqueConstraint(name = "uk_event_external_id", columnNames = "externalId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    /**
     * 공연 시작 얼마 전까지 좌석을 잡을 수 있는지다. QR 티켓이 발급되는 순간이자 티켓을 넘길 수 있는 마지막 순간과
     * 같아서, 쓰거나 제때 넘길 수 없는 표는 팔지 않는다.
     */
    public static final Duration SALES_CLOSE_BEFORE_START = Duration.ofHours(2);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime openAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    // 컬럼 기본값 덕분에 이미 있는 공연 테이블에도 ddl-auto의 ALTER TABLE이 유효하다.
    // 장르가 생기기 전에는 모두 콘서트였다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) not null default 'CONCERT'")
    private EventCategory category = EventCategory.CONCERT;

    /** 공공 공연예술 데이터에서 가져온 공연의 KOPIS 공연 id(mt20id). 직접 만든 공연은 null이다. */
    @Column(length = 40)
    private String externalId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Event(
            String title,
            String venue,
            String description,
            EventCategory category,
            LocalDateTime startAt,
            LocalDateTime openAt,
            EventStatus status) {
        this.title = title;
        this.venue = venue;
        this.description = description;
        this.category = category;
        this.startAt = startAt;
        this.openAt = openAt;
        this.status = status;
    }

    public void importedFrom(String externalId) {
        this.externalId = externalId;
    }

    /** Where the show's data came from, for the "출처" link on the detail page; null for events we created ourselves. */
    public String getSourceUrl() {
        return externalId == null
                ? null
                : "https://www.kopis.or.kr/por/db/pblprfr/pblprfrView.do?menuId=MNU_00020&mt20Id=" + externalId;
    }

    /** 이 공연의 좌석을 잡을 수 있는 마지막 순간이다(그 시각은 포함하지 않는다). */
    public LocalDateTime getSalesCloseAt() {
        return startAt.minus(SALES_CLOSE_BEFORE_START);
    }

    /**
     * 예매가 끝났는지다. 공연의 판매 구간이 끝났거나 누군가 닫은 것으로 표시했다. 저장된 상태가 아니라 시계로 판정하는데,
     * 상태는 다음 스위퍼 주기에야 시계를 따라잡기 때문이다.
     */
    public boolean isSalesClosed(LocalDateTime now) {
        return status == EventStatus.CLOSED || !now.isBefore(getSalesCloseAt());
    }

    public void close() {
        this.status = EventStatus.CLOSED;
    }

    /** Flipped by OpenAlertSweeper once openAt has passed, so the list/detail pages stop saying "오픈예정". */
    public void open() {
        this.status = EventStatus.OPEN;
    }

    public void update(
            String title,
            String venue,
            String description,
            EventCategory category,
            LocalDateTime startAt,
            LocalDateTime openAt,
            EventStatus status) {
        this.title = title;
        this.venue = venue;
        this.description = description;
        this.category = category;
        this.startAt = startAt;
        this.openAt = openAt;
        this.status = status;
    }
}
