package com.ticketing.orderservice.payment;

import com.ticketing.orderservice.order.Orders;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Orders order;

    @Column(nullable = false, length = 20)
    private String method;

    /** 서버 쪽 검증을 위해 결제를 조회할 때 쓰는 PortOne 결제 식별자. */
    @Column(length = 100)
    private String portonePaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private Integer amount;

    private LocalDateTime paidAt;

    // 고객에게 돌려준 원화 금액이다. 늦은 취소로 일부만 환불되면 amount보다 작다. 컬럼 기본값 덕분에
    // 이미 있는 결제 테이블에도 ddl-auto의 ALTER TABLE이 유효하다.
    @Column(nullable = false, columnDefinition = "integer default 0")
    private Integer refundedAmount = 0;

    private LocalDateTime refundedAt;

    public Payment(
            Orders order, String method, String portonePaymentId, PaymentStatus status, Integer amount, LocalDateTime paidAt) {
        this.order = order;
        this.method = method;
        this.portonePaymentId = portonePaymentId;
        this.status = status;
        this.amount = amount;
        this.paidAt = paidAt;
    }

    public void markRefunded(int refundedAmount) {
        this.refundedAmount = refundedAmount;
        this.refundedAt = LocalDateTime.now();
    }
}
