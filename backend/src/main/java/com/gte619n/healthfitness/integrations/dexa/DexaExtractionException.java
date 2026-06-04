package com.gte619n.healthfitness.integrations.dexa;

public class DexaExtractionException extends RuntimeException {
    public DexaExtractionException(String message) {
        super(message);
    }

    public DexaExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
