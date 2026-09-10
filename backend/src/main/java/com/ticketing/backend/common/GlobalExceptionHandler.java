package com.ticketing.backend.common;

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

    /**
     * Concurrent seat reservation attempts can surface as any of these instead of a clean
     * application-level check: the @Version optimistic lock losing a race
     * (ObjectOptimisticLockingFailureException), two inserts colliding on the seat's unique
     * reservation slot (DataIntegrityViolationException), or MySQL detecting a deadlock between
     * the racing transactions and rolling one back (PessimisticLockingFailureException, which
     * covers CannotAcquireLockException). All three mean the same thing to the caller: someone
     * else got the seat first.
     */
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
