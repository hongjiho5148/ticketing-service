package com.ticketing.orderservice.ticket;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "ticket")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Orders order;

    // QR 토큰의 JWT "jti" 클레임에 넣는다. 스캔 엔드포인트는 ticketId가 아니라 이 값으로 티켓을 찾으므로,
    // 가로채서 재생한 토큰은 자기 티켓에만 일치할 수 있다.
    @Column(name = "token_jti", nullable = false, unique = true, length = 36)
    private String tokenJti;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public Ticket(Orders order, String tokenJti) {
        this.order = order;
        this.tokenJti = tokenJti;
        this.status = TicketStatus.ISSUED;
    }
}
