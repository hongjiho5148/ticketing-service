package com.ticketing.orderservice.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 예약 상태입니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "좌석을 찾을 수 없습니다."),
    SEAT_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약된 좌석입니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제된 주문입니다."),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 주문 상태입니다."),
    REFUND_PERIOD_EXPIRED(HttpStatus.CONFLICT, "환불 가능 기간이 지났습니다. 공연 시작 24시간 전까지만 취소할 수 있어요."),
    REFUND_FAILED(HttpStatus.BAD_GATEWAY, "환불 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    EVENT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "좌석/이벤트 정보를 가져올 수 없습니다. 잠시 후 다시 시도해주세요."),
    RESERVATION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "예약 정보를 가져올 수 없습니다. 잠시 후 다시 시도해주세요."),
    TICKET_NOT_ISSUED_YET(HttpStatus.NOT_FOUND, "아직 발급되지 않은 입장권입니다. 공연 시작 2시간 전부터 발급됩니다."),
    TICKET_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 QR 코드입니다."),
    TICKET_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용된 입장권입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
