package com.ticketing.orderservice.payment.portone;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOnePaymentResponse(String status, Amount amount, Method method) {

    public boolean isPaid() {
        return "PAID".equals(status);
    }

    /**
     * What the customer actually paid with, as PortOne reports it - never taken from the client's
     * request. Card -> CARD; easy-pay -> its provider (KAKAOPAY, NAVERPAY, TOSSPAY...).
     */
    public String methodLabel() {
        if (method == null || method.type() == null) {
            return "UNKNOWN";
        }
        return switch (method.type()) {
            case "PaymentMethodCard" -> "CARD";
            case "PaymentMethodEasyPay" -> method.provider() != null ? method.provider() : "EASY_PAY";
            case "PaymentMethodTransfer" -> "TRANSFER";
            case "PaymentMethodVirtualAccount" -> "VIRTUAL_ACCOUNT";
            case "PaymentMethodMobile" -> "MOBILE";
            default -> "OTHER";
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(long total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Method(String type, String provider) {
    }
}
