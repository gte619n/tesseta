package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.util.List;

/**
 * A globally shared, reusable food definition. Stored top-level in
 * {@code foodCatalog/{foodId}} so one definition serves every user.
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
    Instant updatedAt
) {}
