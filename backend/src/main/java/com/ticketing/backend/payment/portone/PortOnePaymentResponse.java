package com.ticketing.backend.payment.portone;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOnePaymentResponse(String status, Amount amount) {

    public boolean isPaid() {
        return "PAID".equals(status);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(long total) {
    }
}
