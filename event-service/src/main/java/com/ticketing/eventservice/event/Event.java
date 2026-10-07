package com.ticketing.eventservice.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

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

    // The column default keeps ddl-auto's ALTER TABLE valid for events that already exist - they
    // were all concerts before genres were introduced.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) not null default 'CONCERT'")
    private EventCategory category = EventCategory.CONCERT;

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
