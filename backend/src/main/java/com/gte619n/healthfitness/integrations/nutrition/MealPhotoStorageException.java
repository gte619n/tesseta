package com.gte619n.healthfitness.integrations.nutrition;

/** Thrown when a meal/label photo cannot be persisted to storage. */
public class MealPhotoStorageException extends RuntimeException {

    public MealPhotoStorageException(String message) {
        super(message);
    }

    public MealPhotoStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
