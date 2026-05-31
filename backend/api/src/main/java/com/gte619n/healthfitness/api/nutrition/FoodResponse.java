package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import java.util.List;

/** Wire representation of a {@link CatalogFood}. */
public record FoodResponse(
    String foodId,
    String name,
    String brand,
    String barcode,
    String category,
    MacrosDto macrosPer100g,
    List<ServingSizeDto> servingSizes,
    int defaultServingIndex,
    FoodSource source,
    String sourceRef,
    FoodStatus status,
    int confirmationCount,
    String imageUrl,
    FoodImageStatus imageStatus,
    String createdBy
) {
    public static FoodResponse from(CatalogFood f) {
        List<ServingSizeDto> servings = f.servingSizes() == null
            ? List.of()
            : f.servingSizes().stream().map(ServingSizeDto::from).toList();
        return new FoodResponse(
            f.foodId(),
            f.name(),
            f.brand(),
            f.barcode(),
            f.category(),
            MacrosDto.from(f.macrosPer100g()),
            servings,
            f.defaultServingIndex(),
            f.source(),
            f.sourceRef(),
            f.status(),
            f.confirmationCount(),
            f.imageUrl(),
            f.imageStatus(),
            f.createdBy()
        );
    }
}
