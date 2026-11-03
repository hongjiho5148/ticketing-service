package com.ticketing.orderservice.payment.portone;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOnePaymentResponse(String status, Amount amount, Method method) {

    public boolean isPaid() {
        return "PAID".equals(status);
    }

    /**
     * 고객이 실제로 무엇으로 결제했는지를 PortOne이 보고한 대로 담는다. 클라이언트 요청에서 가져오지 않는다.
     * 카드 -> CARD, 간편결제 -> 그 제공사(KAKAOPAY, NAVERPAY, TOSSPAY ...).
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
