package com.gte619n.healthfitness.api.error;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global exception handler that converts internal errors to proper HTTP responses.
 * Prevents Firestore/gRPC errors from leaking as 403s via Spring's default error handling.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(String error, String message, Instant timestamp) {}

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        // ResponseStatusException is the canonical way controllers signal a
        // specific HTTP status (e.g. throw new ResponseStatusException(NOT_FOUND)).
        // It extends RuntimeException, so the catch-all below would otherwise
        // collapse 404/400/etc. into 500. Honour the carried status code.
        log.warn("Status exception {}: {}", ex.getStatusCode(), ex.getReason());
        return ResponseEntity
            .status(ex.getStatusCode())
            .body(new ErrorResponse(
                ex.getStatusCode().toString(),
                ex.getReason(),
                Instant.now()
            ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        // AccessDeniedException extends RuntimeException, so without this
        // specific handler the catch-all below would turn admin denials into
        // 500s. Map it to 403 to match Spring Security's default behaviour
        // when the exception originates in the security filter chain.
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse(
                "forbidden",
                ex.getMessage(),
                Instant.now()
            ));
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElement(java.util.NoSuchElementException ex) {
        // Core services (which must stay spring-web-free) raise
        // NoSuchElementException for a missing resource; surface it as 404
        // rather than letting the catch-all collapse it into a 500.
        log.warn("Not found: {}", ex.getMessage());
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(
                "not_found",
                ex.getMessage(),
                Instant.now()
            ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(
                "bad_request",
                ex.getMessage(),
                Instant.now()
            ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex) {
        String exMessage = ex.getMessage();

        // Check if this is a wrapped Firestore error from persistence layer
        if (exMessage != null && exMessage.startsWith("Firestore call")) {
            return handleFirestoreError(ex);
        }

        log.error("Unhandled runtime exception", ex);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse(
                "internal_error",
                "An unexpected error occurred",
                Instant.now()
            ));
    }

    private ResponseEntity<ErrorResponse> handleFirestoreError(RuntimeException ex) {
        Throwable cause = ex.getCause();
        String causeMessage = cause != null ? cause.getMessage() : "";

        log.error("Firestore error: {}", causeMessage, ex);

        String userMessage;
        if (causeMessage.contains("FAILED_PRECONDITION") || causeMessage.contains("requires an index")) {
            userMessage = "Database query failed. A required index may be missing.";
        } else if (causeMessage.contains("UNAVAILABLE")) {
            userMessage = "Database temporarily unavailable. Please retry.";
        } else if (causeMessage.contains("DEADLINE_EXCEEDED")) {
            userMessage = "Database request timed out. Please retry.";
        } else if (causeMessage.contains("PERMISSION_DENIED")) {
            userMessage = "Database access denied.";
        } else if (causeMessage.contains("NOT_FOUND")) {
            userMessage = "Requested resource not found.";
        } else {
            userMessage = "Database error occurred.";
        }

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse(
                "database_error",
                userMessage,
                Instant.now()
            ));
    }
}
