package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.ServingSize;

/** Wire representation of a {@link ServingSize}. */
public record ServingSizeDto(String label, Double grams) {

    public static ServingSizeDto from(ServingSize s) {
        if (s == null) return null;
        return new ServingSizeDto(s.label(), s.grams());
    }

    public ServingSize toServingSize() {
        return new ServingSize(label, grams);
    }
}
