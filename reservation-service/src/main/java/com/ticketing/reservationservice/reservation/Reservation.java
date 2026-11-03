package com.ticketing.reservationservice.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
// 이 인덱스가 없으면 테이블에는 기본 키뿐이라서 reserve()의 사용자별 좌석 수 제한 count와 만료 스위퍼가 지금까지 만들어진
// 모든 예약을 훑는다(취소·만료된 행은 지우지 않는다). 이력이 쌓일수록 비용이 커진다.
@Table(
        name = "reservation",
        indexes = {
            @Index(name = "idx_reservation_user_event_status", columnList = "user_id, event_id, status"),
            @Index(name = "idx_reservation_status_hold_expire", columnList = "status, hold_expire_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 유니크가 아니다. 이 행이 쓰이기 전에 event-service의 hold()가 이미 좌석별 Redis 잠금과 AVAILABLE 상태 검사로
    // 좌석 접근을 직렬화하므로 그것이 실제 동시성 방어선이다. 여기에 DB 수준의 유니크 제약을 걸면 "이 좌석은
    // 평생 한 번만 예약할 수 있다"는 뜻이 추가된다. 같은 seat_id가 이전 행(오래전에 취소/만료된 것까지)과
    // 영원히 충돌해서, 그 좌석에 대한 누군가의 첫 홀드가 취소되거나 만료되는 순간부터 그 좌석의 모든 이후
    // 예약이 영구히 막힌다.
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    // 좌석에서 복사해 둔 값이라 사용자별 구매 한도를 event-service에 묻지 않고 셀 수 있다. 컬럼 기본값은 이 컬럼이
    // 생기기 전의 행에도 ddl-auto가 유효하게 한다(0 = 알 수 없음이며 실제 공연과 일치하지 않으므로 그런 옛 행은 세지 않는다).
    @Column(name = "event_id", nullable = false, columnDefinition = "bigint not null default 0")
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "hold_expire_at", nullable = false)
    private LocalDateTime holdExpireAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Reservation(Long userId, Long seatId, Long eventId, LocalDateTime holdExpireAt) {
        this.userId = userId;
        this.seatId = seatId;
        this.eventId = eventId;
        this.status = ReservationStatus.HOLDING;
        this.holdExpireAt = holdExpireAt;
    }

    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
    }
}
