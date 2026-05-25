package com.gte619n.healthfitness.api.error;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler that converts internal errors to proper HTTP responses.
 * Prevents Firestore/gRPC errors from leaking as 403s via Spring's default error handling.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(String error, String message, Instant timestamp) {}

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
