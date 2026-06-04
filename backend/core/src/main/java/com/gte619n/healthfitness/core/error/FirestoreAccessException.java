package com.gte619n.healthfitness.core.error;

/**
 * Raised by the persistence layer when a Firestore call fails or is interrupted.
 *
 * <p>Lives in {@code core} because it is the throw contract between
 * {@code persistence} (which raises it from the shared {@code await} helper) and
 * {@code api} (whose {@code GlobalExceptionHandler} maps it to an HTTP response).
 * The original gRPC failure is preserved as the cause so the handler can inspect
 * its status for a user-facing message.
 */
public class FirestoreAccessException extends RuntimeException {

    public FirestoreAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
