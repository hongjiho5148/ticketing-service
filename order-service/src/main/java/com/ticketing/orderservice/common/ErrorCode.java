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
    REFUND_PERIOD_EXPIRED(HttpStatus.CONFLICT, "취소 가능 기간이 지났습니다. 공연 시작 24시간 전까지만 취소할 수 있어요."),
    REFUND_QUOTE_CHANGED(HttpStatus.CONFLICT, "환불 금액이 변경됐어요. 새 금액을 확인하고 다시 시도해주세요."),
    REFUND_FAILED(HttpStatus.BAD_GATEWAY, "환불 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    EVENT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "좌석/이벤트 정보를 가져올 수 없습니다. 잠시 후 다시 시도해주세요."),
    RESERVATION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "예약 정보를 가져올 수 없습니다. 잠시 후 다시 시도해주세요."),
    ORDER_NOT_PENDING(HttpStatus.CONFLICT, "결제 대기 중인 주문에만 적용할 수 있어요."),
    PAYMENT_AMOUNT_TOO_LOW(HttpStatus.BAD_REQUEST, "결제 금액은 최소 100원 이상이어야 해요."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 쿠폰 코드예요."),
    COUPON_NOT_USABLE(HttpStatus.CONFLICT, "사용 기간이 지났거나 모두 소진된 쿠폰이에요."),
    COUPON_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용한 쿠폰이에요."),
    COUPON_CODE_EXISTS(HttpStatus.CONFLICT, "이미 존재하는 쿠폰 코드예요."),
    POINTS_INVALID(HttpStatus.BAD_REQUEST, "사용할 포인트가 올바르지 않아요."),
    POINTS_INSUFFICIENT(HttpStatus.CONFLICT, "보유 포인트가 부족해요."),
    TICKET_NOT_ISSUED_YET(HttpStatus.NOT_FOUND, "아직 발급되지 않은 입장권입니다. 공연 시작 2시간 전부터 발급됩니다."),
    TICKET_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 QR 코드입니다."),
    TICKET_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용된 입장권입니다."),
    AUTH_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해주세요."),
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "양도 내역을 찾을 수 없어요."),
    TRANSFER_RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 이메일로 가입한 회원을 찾을 수 없어요."),
    TRANSFER_TO_SELF(HttpStatus.BAD_REQUEST, "본인에게는 양도할 수 없어요."),
    TRANSFER_NOT_ALLOWED(HttpStatus.CONFLICT, "양도할 수 없는 티켓이에요. 결제 완료된 티켓만, 한 번만 양도할 수 있어요."),
    TRANSFER_CLOSED(HttpStatus.CONFLICT, "양도 가능 기간이 지났어요. 공연 시작 2시간 전까지만 양도할 수 있어요."),
    TRANSFER_ALREADY_PENDING(HttpStatus.CONFLICT, "이미 수락 대기 중인 양도가 있어요."),
    TRANSFER_NOT_PENDING(HttpStatus.CONFLICT, "이미 처리된 양도예요."),
    ORDER_TRANSFERRED(HttpStatus.CONFLICT, "양도한 티켓은 취소할 수 없어요."),
    ORDER_TRANSFER_PENDING(HttpStatus.CONFLICT, "양도 대기 중인 티켓이에요. 양도를 먼저 취소해주세요."),
    IDENTITY_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "구매자 본인 확인에 동의해야 결제할 수 있어요."),
    IDENTITY_VERIFICATION_REQUIRED(HttpStatus.CONFLICT, "결제 전에 구매자 본인 확인이 필요해요.");

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
