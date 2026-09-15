package com.ticketing.reservationservice.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 예약 상태입니다."),
    PASS_TOKEN_REQUIRED(HttpStatus.FORBIDDEN, "대기열을 통과한 뒤에만 예약할 수 있습니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "좌석을 찾을 수 없습니다."),
    SEAT_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약된 좌석입니다."),
    EVENT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "좌석/이벤트 정보를 가져올 수 없습니다. 잠시 후 다시 시도해주세요."),
    QUEUE_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "대기열 정보를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.");

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
