package com.gte619n.healthfitness.core.nutrition;

/** Provenance of a catalog food. Load-bearing for licensing (see ADR-0006). */
public enum FoodSource {
    USDA,
    OPEN_FOOD_FACTS,
    USER,
    GEMINI_PHOTO,
    GEMINI_LABEL
}
