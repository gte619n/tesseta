package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.util.List;

/**
 * A globally shared, reusable food definition. Stored top-level in
 * {@code foodCatalog/{foodId}} so one definition serves every user.
 *
 * <p>{@code alcohol} is non-null only for drinks (IMPL-DRINK-01), i.e. foods with
 * {@code category = "drink"}; it carries the drink's ABV/volume and derived
 * standard-drink facts. {@code archivedAt} soft-deletes a food (used by the drink
 * catalog's archive, D20): an archived food is hidden from listings but its
 * document — and any already-logged entries that froze its macros — remain intact.
 */
public record CatalogFood(
    String foodId,
    String name,
    String nameLower,
    String brand,
    String barcode,
    String category,
    Macros macrosPer100g,
    List<ServingSize> servingSizes,
    int defaultServingIndex,
    FoodSource source,
    String sourceRef,
    FoodStatus status,
    int confirmationCount,
    Instant verifiedAt,
    String imageUrl,
    FoodImageStatus imageStatus,
    String createdBy,
    Instant createdAt,
    Instant updatedAt,
    AlcoholInfo alcohol,
    Instant archivedAt
) {
    /** True when this food is an IMPL-DRINK-01 alcoholic drink. */
    public boolean isDrink() {
        return "drink".equalsIgnoreCase(category);
    }

    /** True when this food has been soft-deleted (archived). */
    public boolean isArchived() {
        return archivedAt != null;
    }
}
