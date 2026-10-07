package com.ticketing.orderservice.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** Proof that the buyer was verified for one order, kept so a disputed purchase can be traced to what they confirmed. */
@Entity
@Table(
        name = "verification_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_verification_order", columnNames = "order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Which provider vouched for the buyer (SELF_ATTESTED today).
    @Column(nullable = false, length = 30)
    private String method;

    // Version of the wording the buyer saw and agreed to, so old records stay interpretable if the text changes.
    @Column(name = "statement_version", nullable = false, length = 10)
    private String statementVersion;

    @CreationTimestamp
    @Column(name = "verified_at", nullable = false, updatable = false)
    private LocalDateTime verifiedAt;

    public VerificationRecord(Long orderId, Long userId, String method, String statementVersion) {
        this.orderId = orderId;
        this.userId = userId;
        this.method = method;
        this.statementVersion = statementVersion;
    }
}
