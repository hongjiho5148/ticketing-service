package com.ticketing.eventservice.common;

import java.time.LocalDateTime;

public record ErrorResponse(String code, String message, LocalDateTime timestamp) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message, LocalDateTime.now());
    }
}
