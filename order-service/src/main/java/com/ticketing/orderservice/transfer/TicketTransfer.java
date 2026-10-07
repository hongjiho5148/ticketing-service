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
 * One request to hand a paid order's ticket to another user. The ticket itself may not exist yet
 * (QR codes are issued only 2 hours before the show), so a transfer is about the order; accepting
 * it flips Orders.ownerId and everything ticket-related follows the holder from there.
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

    // Denormalized so the transfer list needs no per-row lookup against auth-service.
    @Column(name = "from_name", nullable = false, length = 100)
    private String fromName;

    @Column(name = "to_email_masked", nullable = false, length = 100)
    private String toEmailMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    // Equals orderId while PENDING and null afterwards. The unique constraint on it is what
    // guarantees at most one open transfer per order even if two requests race past the app-level check.
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
