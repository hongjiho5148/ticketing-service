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

/** 한 주문에 대해 구매자가 확인됐다는 증거다. 분쟁이 된 구매를 구매자가 확인한 내용까지 추적할 수 있도록 남긴다. */
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

    // 어느 제공자가 구매자를 보증했는지(현재는 SELF_ATTESTED).
    @Column(nullable = false, length = 30)
    private String method;

    // 구매자가 보고 동의한 문구의 버전이다. 문구가 바뀌어도 오래된 기록을 해석할 수 있게 한다.
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
