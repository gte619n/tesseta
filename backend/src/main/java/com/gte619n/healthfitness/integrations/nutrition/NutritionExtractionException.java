package com.gte619n.healthfitness.integrations.nutrition;

/**
 * Thrown when a Gemini meal-photo or nutrition-label extraction fails (empty
 * response, no tool call, or unparseable args). Controllers map this to a
 * 422 Unprocessable Entity.
 */
public class NutritionExtractionException extends RuntimeException {

    public NutritionExtractionException(String message) {
        super(message);
    }

    public NutritionExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
