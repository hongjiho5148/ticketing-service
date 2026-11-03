package com.ticketing.orderservice.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 결제창을 열기 전에 서버가 한 주문에 대해 내준 결제 id다. 두 가지가 이것에 의존한다.
 * pay()는 그 주문에 등록된 결제 id만 받아들이고(그렇지 않으면 결제된 한 건을 같은 가격의 다른 모든 주문에
 * 내밀 수 있다), 조정 작업은 브라우저가 아무 보고도 하지 않았을 때도 PortOne에서 결제를 조회할 수 있다.
 */
@Entity
@Table(
        name = "payment_attempt",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_attempt_payment_id", columnNames = "payment_id"),
        indexes = @Index(name = "idx_payment_attempt_order", columnList = "order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payment_id", nullable = false, length = 100)
    private String paymentId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PaymentAttempt(Long orderId, String paymentId) {
        this.orderId = orderId;
        this.paymentId = paymentId;
    }
}
