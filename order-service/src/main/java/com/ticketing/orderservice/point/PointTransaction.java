package com.ticketing.orderservice.point;

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

/** 추가만 하는 원장이다. PointAccount의 잔액은 언제든 이 행들을 합산해서 다시 구할 수 있다. */
@Entity
@Table(name = "point_transaction", indexes = @Index(name = "idx_point_tx_user", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 부호가 있다. 양수는 잔액에 더하고 음수는 잔액에서 뺀다. */
    @Column(nullable = false)
    private Long delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointTransactionType type;

    @Column(nullable = false)
    private Long orderId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PointTransaction(Long userId, Long delta, PointTransactionType type, Long orderId) {
        this.userId = userId;
        this.delta = delta;
        this.type = type;
        this.orderId = orderId;
    }
}
