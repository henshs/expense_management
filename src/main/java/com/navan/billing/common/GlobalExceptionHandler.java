package com.navan.billing.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns exceptions into consistent JSON error bodies: {"error": code, "message": text, ...}. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.details());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .sorted()
                .toList();
        return error(HttpStatus.BAD_REQUEST, "validation_failed", "Request body is invalid", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "malformed_json", "Request body is not valid JSON for this endpoint", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "bad_request", "Invalid value for parameter '" + e.getName() + "'", null);
    }

    /** Backstop: a UNIQUE / foreign-key constraint fired that the service did not pre-check (a race). */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> handleIntegrity(org.springframework.dao.DataIntegrityViolationException e) {
        log.info("Constraint violation mapped to 409: {}", e.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "conflict", "The request conflicts with the current state; retry", null);
    }

    /** Another request held a row lock for longer than LOCK_TIMEOUT. Safe to retry. */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> handleLock(org.springframework.dao.PessimisticLockingFailureException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "busy", "The resource is busy; retry", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error", null);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message, List<String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        if (errors != null) {
            body.put("errors", errors);
        }
        return ResponseEntity.status(status).body(body);
    }
}
