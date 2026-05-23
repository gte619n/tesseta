package com.gte619n.healthfitness.integrations.bloodtest;

public class BloodTestExtractionException extends RuntimeException {
    public BloodTestExtractionException(String message) {
        super(message);
    }
    public BloodTestExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
