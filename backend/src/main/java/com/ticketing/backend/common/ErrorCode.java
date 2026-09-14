package com.ticketing.backend.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "좌석을 찾을 수 없습니다."),
    SEAT_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약된 좌석입니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 예약 상태입니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제된 주문입니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다. 메일함을 확인해주세요."),
    INVALID_VERIFICATION_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않은 인증 링크입니다."),
    VERIFICATION_TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "인증 링크가 만료되었습니다. 다시 가입해주세요."),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 주문 상태입니다."),
    REFUND_PERIOD_EXPIRED(HttpStatus.CONFLICT, "환불 가능 기간이 지났습니다. 공연 시작 24시간 전까지만 취소할 수 있어요."),
    REFUND_FAILED(HttpStatus.BAD_GATEWAY, "환불 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    QUEUE_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "대기열 정보를 찾을 수 없습니다. 다시 입장해주세요."),
    PASS_TOKEN_REQUIRED(HttpStatus.FORBIDDEN, "대기열을 통과한 뒤에만 예약할 수 있습니다."),
    EVENT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "좌석/이벤트 정보를 가져올 수 없습니다. 잠시 후 다시 시도해주세요.");

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
