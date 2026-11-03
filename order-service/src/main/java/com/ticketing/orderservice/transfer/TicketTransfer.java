package com.ticketing.orderservice.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 결제된 주문의 티켓을 다른 사용자에게 넘기는 요청 하나다. 티켓 자체는 아직 없을 수 있어서(QR은 공연 2시간 전에야
 * 발급된다) 양도는 주문에 대한 것이다. 수락하면 Orders.ownerId가 바뀌고, 티켓과 관련된 모든 것이 그때부터 소지자를 따른다.
 */
@Entity
@Table(
        name = "ticket_transfer",
        uniqueConstraints = @UniqueConstraint(name = "uk_transfer_pending_order", columnNames = "pending_order_id"),
        indexes = {
            @Index(name = "idx_transfer_from", columnList = "from_user_id"),
            @Index(name = "idx_transfer_to", columnList = "to_user_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;

    @Column(name = "to_user_id", nullable = false)
    private Long toUserId;

    // 양도 목록이 행마다 auth-service를 조회하지 않아도 되도록 값을 복사해 둔다.
    @Column(name = "from_name", nullable = false, length = 100)
    private String fromName;

    @Column(name = "to_email_masked", nullable = false, length = 100)
    private String toEmailMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    // PENDING인 동안은 orderId와 같고 그 뒤에는 null이다. 이 값의 유니크 제약이 있어서, 두 요청이 앱 수준 검사를
    // 함께 통과해도 주문당 열린 양도는 최대 하나로 보장된다.
    @Column(name = "pending_order_id")
    private Long pendingOrderId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    public TicketTransfer(Long orderId, Long fromUserId, Long toUserId, String fromName, String toEmailMasked) {
        this.orderId = orderId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.fromName = fromName;
        this.toEmailMasked = toEmailMasked;
        this.status = TransferStatus.PENDING;
        this.pendingOrderId = orderId;
    }
}
