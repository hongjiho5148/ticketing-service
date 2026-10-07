package com.ticketing.orderservice.transfer;

import com.ticketing.orderservice.order.OrderStatus;
import com.ticketing.orderservice.order.Orders;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * When a ticket may change hands. The window closes at the same moment QR issuance opens (default
 * 2 hours before the show), so a QR code never has to be re-issued for a new holder, and one
 * transfer per ticket keeps it from turning into a resale chain.
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
