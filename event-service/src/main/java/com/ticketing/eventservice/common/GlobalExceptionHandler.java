package com.ticketing.eventservice.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus()).body(ErrorResponse.of(e.getErrorCode()));
    }

    /** 분리 전에 backend가 처리하던 것과 같은 좌석 경합이다. SeatLockService 참고. */
    @ExceptionHandler({
        ObjectOptimisticLockingFailureException.class,
        DataIntegrityViolationException.class,
        PessimisticLockingFailureException.class
    })
    public ResponseEntity<ErrorResponse> handleSeatContention(Exception e) {
        return ResponseEntity.status(ErrorCode.SEAT_ALREADY_RESERVED.getStatus())
                .body(ErrorResponse.of(ErrorCode.SEAT_ALREADY_RESERVED));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
    }
}
