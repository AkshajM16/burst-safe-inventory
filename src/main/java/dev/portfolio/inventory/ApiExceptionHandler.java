package dev.portfolio.inventory;

import dev.portfolio.inventory.ApiModels.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({InvalidRequestException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalid(Exception error) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", error.getMessage());
    }

    @ExceptionHandler(ItemNotFoundException.class)
    ResponseEntity<ErrorResponse> missing(ItemNotFoundException error) {
        return error(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", error.getMessage());
    }

    @ExceptionHandler({ReservationConflictException.class, DuplicateKeyException.class})
    ResponseEntity<ErrorResponse> conflict(Exception error) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", error.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> database(DataAccessException error) {
        log.error("database operation failed", error);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OVERLOADED", "database unavailable or pool timeout");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> internal(Exception error) {
        log.error("unhandled request failure", error);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal failure");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, MDC.get("requestId")));
    }
}
