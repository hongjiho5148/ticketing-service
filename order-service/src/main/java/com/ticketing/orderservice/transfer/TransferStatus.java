package com.ticketing.orderservice.transfer;

public enum TransferStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    /** Withdrawn by the sender before the recipient answered. */
    CANCELLED,
    /** The window closed (or the order stopped being transferable) before the recipient answered. */
    EXPIRED
}
