package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.EntryAnalysisStatus;
import com.gte619n.healthfitness.core.nutrition.EntrySource;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.MealType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire representation of a {@link FoodEntry}.
 *
 * <p>{@code imageUrl}/{@code imageStatus} are NOT stored on the entry — they are
 * joined in from the entry's catalog food ({@code foodId}) at read time so the
 * clients can render the generated studio image alongside each logged food.
 * Manual ("quick add") entries carry no {@code foodId}, so they report
 * {@code imageStatus = NONE} and a null url (the clients fall back to a
 * placeholder).
 */
public record EntryResponse(
    String entryId,
    LocalDate date,
    MealType meal,
    String foodId,
    String foodName,
    String servingLabel,
    Double servingGrams,
    Double quantity,
    MacrosDto macros,
    EntrySource source,
    String imageUrl,
    FoodImageStatus imageStatus,
    EntryAnalysisStatus analysisStatus,
    List<IngredientResponse> ingredients,
    // When the entry was first logged (server timestamp). Lets clients order
    // entries on a cross-source activity timeline; null for a not-yet-persisted
    // placeholder (e.g. an in-flight photo capture).
    Instant createdAt
) {
    /** Bare mapping with no catalog image (used where the food isn't loaded). */
    public static EntryResponse from(FoodEntry e) {
        return from(e, null, FoodImageStatus.NONE, null);
    }

    /** Mapping enriched with the catalog food's generated image, when known. */
    public static EntryResponse from(FoodEntry e, String imageUrl, FoodImageStatus imageStatus) {
        return from(e, imageUrl, imageStatus, null);
    }

    /**
     * Full mapping. For a composite (photo-logged) meal the {@code imageUrl}/
     * {@code imageStatus} are the finished-meal image and {@code ingredients}
     * lists the components (each with its own raw-ingredient image).
     */
    public static EntryResponse from(
        FoodEntry e,
        String imageUrl,
        FoodImageStatus imageStatus,
        List<IngredientResponse> ingredients
    ) {
        return new EntryResponse(
            e.entryId(),
            e.date(),
            e.meal(),
            e.foodId(),
            e.foodName(),
            e.servingLabel(),
            e.servingGrams(),
            e.quantity(),
            MacrosDto.from(e.macros()),
            e.source(),
            imageUrl,
            imageStatus != null ? imageStatus : FoodImageStatus.NONE,
            e.analysisStatus() != null ? e.analysisStatus() : EntryAnalysisStatus.NONE,
            ingredients,
            e.createdAt()
        );
    }

    /** One ingredient of a composite meal, with its raw-ingredient image. */
    public record IngredientResponse(
        String name,
        String foodId,
        String servingLabel,
        Double servingGrams,
        Double quantity,
        MacrosDto macros,
        MacrosDto macrosPer100g,
        String imageUrl,
        FoodImageStatus imageStatus
    ) {}
}
