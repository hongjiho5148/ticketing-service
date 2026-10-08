package com.ticketing.eventservice.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "좌석을 찾을 수 없습니다."),
    SEAT_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약된 좌석입니다."),
    OPEN_ALERT_NOT_AVAILABLE(HttpStatus.CONFLICT, "이미 예매가 열렸거나 알림을 받을 수 없는 공연이에요."),
    WAITLIST_NOT_AVAILABLE(HttpStatus.CONFLICT, "예매 가능한 좌석이 남아 있어요. 취소표 알림은 매진된 공연에서만 신청할 수 있어요."),
    SEAT_NOT_MODIFIABLE(HttpStatus.CONFLICT, "이미 판매된 좌석은 수정할 수 없습니다."),
    SECTION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 등록된 구역입니다."),
    REVIEW_NOT_ELIGIBLE(HttpStatus.FORBIDDEN, "결제 완료한 공연에만 후기를 남길 수 있어요."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 후기를 작성했어요."),
    KOPIS_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "KOPIS 서비스키가 설정되지 않았어요. KOPIS_SERVICE_KEY 환경변수를 확인해주세요."),
    KOPIS_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "KOPIS에서 데이터를 가져오지 못했어요. 서비스키와 네트워크를 확인해주세요."),
    ORDER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "주문 정보를 가져올 수 없습니다. 잠시 후 다시 시도해주세요.");

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
