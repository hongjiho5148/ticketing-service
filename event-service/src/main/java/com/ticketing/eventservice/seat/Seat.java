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
     * hold()가 호출될 때마다 설정한다(backend가 자기 예약 홀드가 언제 만료되는지 알려 준다).
     * SeatHoldExpirySweeper는 이것을 이용해서, 호출한 쪽(backend)이 해제를 끝내 요청하지 않아도
     * 멈춰 있는 HOLD를 스스로 치유한다. 예를 들어 홀드에 성공한 뒤 자기 Reservation 행을 저장하기 전에 죽은 경우이고,
     * 그 행은 이제 이 서비스와 다른 서비스/DB에 있다.
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

    public void changePrice(Integer price) {
        this.price = price;
    }

    public void hold(LocalDateTime holdExpireAt) {
        this.status = SeatStatus.HOLD;
        this.holdExpireAt = holdExpireAt;
    }

    /**
     * 이 좌석이 HOLD 상태이고 그 홀드가 {@code holdExpireAt}에 끝나는 홀드와 같을 때 true다. 만료 시각이 홀드의 정체성
     * 역할을 한다. 좌석은 해제된 뒤에야 다시 잡을 수 있고 그것은 1ms 허용 오차보다 훨씬 오래 걸리므로 서로 다른
     * 두 홀드가 같다고 비교되는 일은 없다. (허용 오차가 있는 것은 두 서비스가 시각을 각자의 DB에 저장하고
     * MySQL이 소수 초를 반올림하기 때문이다.)
     */
    public boolean isHeldWith(LocalDateTime expectedHoldExpireAt) {
        return status == SeatStatus.HOLD && sameHold(expectedHoldExpireAt);
    }

    /** 이 좌석이 정확히 그 홀드로 팔렸으면 true다. 반복된 판매 처리(다시 시도한 결제)가 조용히 성공하게 한다. */
    public boolean isSoldFrom(LocalDateTime expectedHoldExpireAt) {
        return status == SeatStatus.SOLD && sameHold(expectedHoldExpireAt);
    }

    private boolean sameHold(LocalDateTime expected) {
        return holdExpireAt != null
                && expected != null
                && Math.abs(java.time.Duration.between(holdExpireAt, expected).toNanos()) < 1_000_000L;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.holdExpireAt = null;
    }

    /** holdExpireAt을 일부러 남긴다. 어느 홀드가 이 좌석을 샀는지를 기록하기 때문이다(isSoldFrom 참고). 스윕 대상은 HOLD 좌석뿐이다. */
    public void sell() {
        this.status = SeatStatus.SOLD;
    }
}
