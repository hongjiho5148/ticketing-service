package com.ticketing.eventservice.seat;

import com.ticketing.eventservice.event.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, length = 20)
    private String seatGrade;

    /** Visual block within the venue, e.g. "1층 101구역" - groups seats for the section-based seat map. */
    @Column(nullable = false, length = 50)
    private String section;

    @Column(nullable = false)
    private Integer rowNo;

    @Column(nullable = false)
    private Integer seatNumber;

    @Column(nullable = false, length = 20)
    private String seatNo;

    @Column(nullable = false)
    private Integer price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    /**
     * Set whenever hold() is called (backend tells us when its own reservation hold would expire).
     * SeatHoldExpirySweeper uses this to self-heal a stuck HOLD even if the caller (backend) never
     * follows up with a release - e.g. it crashed between a successful hold and saving its own
     * Reservation row, which now lives in a different service/DB than this one.
     */
    private LocalDateTime holdExpireAt;

    @Version
    @Column(nullable = false)
    private Integer version;

    public Seat(Event event, String seatGrade, String section, Integer rowNo, Integer seatNumber, Integer price) {
        this.event = event;
        this.seatGrade = seatGrade;
        this.section = section;
        this.rowNo = rowNo;
        this.seatNumber = seatNumber;
        this.seatNo = rowNo + "열 " + seatNumber + "번";
        this.price = price;
        this.status = SeatStatus.AVAILABLE;
    }

    public void hold(LocalDateTime holdExpireAt) {
        this.status = SeatStatus.HOLD;
        this.holdExpireAt = holdExpireAt;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.holdExpireAt = null;
    }

    public void sell() {
        this.status = SeatStatus.SOLD;
        this.holdExpireAt = null;
    }
}
