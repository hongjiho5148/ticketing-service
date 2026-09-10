package com.ticketing.backend.seat;

import com.ticketing.backend.event.Event;
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

    public void hold() {
        this.status = SeatStatus.HOLD;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
    }

    public void sell() {
        this.status = SeatStatus.SOLD;
    }
}
