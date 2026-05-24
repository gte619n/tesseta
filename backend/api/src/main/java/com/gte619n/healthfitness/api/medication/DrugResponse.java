package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugCategory;
import com.gte619n.healthfitness.core.medication.DrugForm;
import java.util.List;

/**
 * API response for a drug catalog entry.
 */
public record DrugResponse(
    String drugId,
    String name,
    List<String> aliases,
    DrugCategory category,
    DrugForm form,
    String defaultUnit,
    List<String> commonDoses,
    String imageUrl,
    String imageFallback,
    List<String> suggestedMarkers,
    String description
) {
    public static DrugResponse from(Drug d) {
        return new DrugResponse(
            d.drugId(),
            d.name(),
            d.aliases(),
            d.category(),
            d.form(),
            d.defaultUnit(),
            d.commonDoses(),
            d.imageUrl(),
            d.imageFallback(),
            d.suggestedMarkers(),
            d.description()
        );
    }
}
