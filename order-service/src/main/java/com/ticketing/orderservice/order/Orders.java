package com.ticketing.orderservice.order;

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
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Orders {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private Long reservationId;

    // 주문을 만들 때 예약에서 복사해 둔 값이다. 이후 바뀌지 않으므로 여기에 두면 주문 내역을 그릴 때마다
    // reservation-service를 왕복하지 않아도 된다.
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    // 이것도 같은 이유로 주문을 만들 때 event-service에서 복사해 둔 값이다. QR 티켓 발급 스위퍼가 "곧 공연이
    // 시작하는 결제 주문"을 로컬 쿼리로 찾을 수 있고, 주기마다 후보 주문 하나당 event-service를 호출하지 않아도 된다.
    @Column(name = "event_start_at", nullable = false)
    private LocalDateTime eventStartAt;

    // 이것도 생성 시점에 event-service에서 복사해 둔 값이다. event-service가 모든 주문의 seatId를 eventId로
    // 직접 풀어 보지 않고, 여기의 쿼리 한 번으로 "이 사용자가 이 공연의 결제 주문이 있는가"(후기 작성 자격)를 확인할 수 있다.
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    // D-1 알림 메일을 보낸 뒤 설정한다. ReminderSweeper가 매분 돌 때 "공연 24~25시간 전" 구간을 지나는
    // 동안 같은 메일을 매번 다시 보내지 않게 한다.
    @Column(name = "reminder_sent_at")
    private LocalDateTime reminderSentAt;

    // 고객에게 실제로 청구되는 금액이다. 좌석 가격에서 시작하고 쿠폰/포인트가 줄인다. 내역은
    // couponCode/discountAmount/pointsUsed에 남는다(원가 = 합계 + 할인 + 포인트).
    @Column(nullable = false)
    private Integer totalPrice;

    @Column(length = 30)
    private String couponCode;

    // 컬럼 기본값 덕분에 이미 있는 주문 테이블에도 ddl-auto의 ALTER TABLE이 유효하다.
    @Column(nullable = false, columnDefinition = "integer default 0")
    private Integer discountAmount = 0;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private Integer pointsUsed = 0;

    // 양도된 뒤 티켓을 가진 사람이다. null이면 양도된 적이 없어서 구매자(userId)가 그대로 가지고 있다.
    // userId는 항상 구매자로 남는다. 결제, 환불, 혜택 기록은 구매자의 몫이다.
    @Column(name = "owner_id")
    private Long ownerId;

    // 이 주문의 포인트/쿠폰이 소모되고 구매 적립이 지급되면 (같은 트랜잭션 안에서) 설정하고, 그것이 되돌려지면
    // 지운다. pay()는 네트워크 호출 사이마다 짧은 트랜잭션 여러 개로 돌기 때문에, 일부가 실패한 뒤의 재시도가
    // 혜택을 두 번 소모해서는 안 된다.
    @Column(name = "benefits_redeemed_at")
    private LocalDateTime benefitsRedeemedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Orders(
            Long userId, Long reservationId, Long seatId, LocalDateTime eventStartAt, Long eventId, Integer totalPrice) {
        this.userId = userId;
        this.reservationId = reservationId;
        this.seatId = seatId;
        this.eventStartAt = eventStartAt;
        this.eventId = eventId;
        this.totalPrice = totalPrice;
        this.status = OrderStatus.PENDING;
    }

    public int originalPrice() {
        return totalPrice + discountAmount + pointsUsed;
    }

    public void applyBenefits(String couponCode, int discountAmount, int pointsUsed) {
        int original = originalPrice();
        this.couponCode = couponCode;
        this.discountAmount = discountAmount;
        this.pointsUsed = pointsUsed;
        this.totalPrice = original - discountAmount - pointsUsed;
    }

    /** 이 티켓을 쓸 수 있는 사용자. 양도된 뒤에는 받은 사람, 아니면 구매자다. */
    public Long holderId() {
        return ownerId != null ? ownerId : userId;
    }

    public boolean wasTransferred() {
        return ownerId != null;
    }

    public void transferTo(Long newHolderId) {
        this.ownerId = newHolderId;
    }

    public boolean isBenefitsRedeemed() {
        return benefitsRedeemedAt != null;
    }

    public void markBenefitsRedeemed() {
        this.benefitsRedeemedAt = LocalDateTime.now();
    }

    public void clearBenefitsRedeemed() {
        this.benefitsRedeemedAt = null;
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
    }

    public void markReminderSent() {
        this.reminderSentAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = OrderStatus.FAILED;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    public void markPartiallyRefunded() {
        this.status = OrderStatus.PARTIALLY_REFUNDED;
    }
}
