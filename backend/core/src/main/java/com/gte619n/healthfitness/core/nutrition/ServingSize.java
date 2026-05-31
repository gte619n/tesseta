package com.gte619n.healthfitness.core.nutrition;

/** A named portion of a catalog food, e.g. {@code ("1 cup", 240.0)}. */
public record ServingSize(String label, Double grams) {}
