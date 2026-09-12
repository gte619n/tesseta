package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One logged food on a given day and meal. {@code macros} is a snapshot frozen
 * at log time so editing the underlying catalog food never rewrites history.
 *
 * <p>A meal logged from a photo is a <em>composite</em> entry: {@code foodId} is
 * null, {@code ingredients} holds its components (each with its own
 * raw-ingredient image), and {@code mealImageUrl}/{@code mealImageStatus} carry
 * the generated image of the finished plated meal. A plain single-food entry
 * leaves {@code ingredients} null and the meal-image fields unset.
 *
 * <p>{@code analysisStatus} tracks the background photo-analysis lifecycle: a
 * freshly captured photo logs a placeholder entry as {@code ANALYZING} and a
 * background task fills it in and flips it to {@code READY}. Entries created any
 * other way are {@code NONE}.
 *
 * <p>{@code contentHash} is the SHA-256 hex of the captured photo's original
 * bytes (null for entries not logged from a photo). It dedupes re-uploads of the
 * same image so a second capture of an identical photo silently returns the
 * existing entry instead of spending another Gemini call — see
 * {@code MealCaptureService.captureMeal} and
 * {@code FoodEntryRepository.findByContentHash}.
 */
public record FoodEntry(
    String userId,
    LocalDate date,
    String entryId,
    MealType meal,
    String foodId,
    String foodName,
    String servingLabel,
    Double servingGrams,
    Double quantity,
    Macros macros,
    String photoRef,
    String contentHash,
    EntrySource source,
    List<CompositeIngredient> ingredients,
    String mealImageUrl,
    FoodImageStatus mealImageStatus,
    EntryAnalysisStatus analysisStatus,
    Instant createdAt,
    Instant updatedAt,
    // "Remove Leftovers" state (IMPL-LEFTOVER-01). Null for entries with no
    // leftover activity. When applied, this entry's live macros/ingredients are
    // the CONSUMED values (spec D9); the as-served baseline lives here.
    Leftover leftover
) {
    /** True when this entry is a photo-logged meal with sub-ingredients. */
    public boolean isComposite() {
        return ingredients != null && !ingredients.isEmpty();
    }

    /** True while the background photo analysis is still running. */
    public boolean isAnalyzing() {
        return analysisStatus == EntryAnalysisStatus.ANALYZING;
    }

    /** True when a "Remove Leftovers" pass has started (any status). */
    public boolean hasLeftover() {
        return leftover != null;
    }

    /**
     * True when this entry can accept a "Remove Leftovers" pass (spec D5): it must
     * be a photo-logged composite meal (has ingredients) with a retained original
     * photo to compare against, and not still analyzing its initial capture.
     */
    public boolean leftoverEligible() {
        return isComposite()
            && photoRef != null && !photoRef.isBlank()
            && analysisStatus != EntryAnalysisStatus.ANALYZING;
    }
}
