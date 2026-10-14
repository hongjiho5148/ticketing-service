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
 * A payment id the server handed out for one order before the payment window opened. Two things depend on it:
 * pay() only accepts a payment id registered for that very order (otherwise one paid payment could be presented for
 * every other order of the same price), and the reconciler can look the payment up at PortOne even when the browser
 * never reported back.
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
