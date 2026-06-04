package com.gte619n.healthfitness.integrations.nutrition;

/** Thrown when a generated food studio image cannot be persisted to storage. */
public class FoodImageStorageException extends RuntimeException {

    public FoodImageStorageException(String message) {
        super(message);
    }

    public FoodImageStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
