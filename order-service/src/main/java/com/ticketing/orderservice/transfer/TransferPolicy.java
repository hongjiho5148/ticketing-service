package com.ticketing.orderservice.transfer;

import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 티켓을 넘길 수 있는 시기다. 창은 QR 발급이 열리는 순간(기본 공연 2시간 전)에 닫히므로 새 소지자를 위해
 * QR을 다시 발급할 일이 없고, 티켓당 양도를 한 번으로 제한해서 되팔기 사슬이 되는 것을 막는다.
 */
@Component
public class TransferPolicy {

    private final long cutoffHours;

    public TransferPolicy(@Value("${transfer.cutoff-hours}") long cutoffHours) {
        this.cutoffHours = cutoffHours;
    }

    public boolean isOpen(Orders order, LocalDateTime now) {
        return order.getEventStartAt().minusHours(cutoffHours).isAfter(now);
    }

    public boolean isTransferable(Orders order, boolean hasPendingTransfer, LocalDateTime now) {
        return order.getStatus() == OrderStatus.PAID
                && !order.wasTransferred()
                && !hasPendingTransfer
                && isOpen(order, now);
    }
}
