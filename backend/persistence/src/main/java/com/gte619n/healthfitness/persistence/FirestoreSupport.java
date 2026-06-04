package com.gte619n.healthfitness.persistence;

import com.google.api.core.ApiFuture;
import com.gte619n.healthfitness.core.error.FirestoreAccessException;
import java.util.concurrent.ExecutionException;

/**
 * Shared helpers for the Firestore repository implementations.
 *
 * <p>{@link #await(ApiFuture)} blocks on a Firestore {@code ApiFuture} and
 * normalizes failures to {@link FirestoreAccessException}, preserving the
 * underlying gRPC cause. This replaces the identical private {@code await}
 * method that previously lived in every repository.
 */
public final class FirestoreSupport {

    private FirestoreSupport() {}

    public static <T> T await(ApiFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirestoreAccessException("Firestore call interrupted", e);
        } catch (ExecutionException e) {
            throw new FirestoreAccessException("Firestore call failed", e.getCause());
        }
    }
}
