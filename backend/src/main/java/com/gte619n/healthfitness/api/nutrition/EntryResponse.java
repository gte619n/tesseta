package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.CompositeIngredient;
import com.gte619n.healthfitness.core.nutrition.EntryAnalysisStatus;
import com.gte619n.healthfitness.core.nutrition.EntrySource;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.Leftover;
import com.gte619n.healthfitness.core.nutrition.LeftoverProposal;
import com.gte619n.healthfitness.core.nutrition.LeftoverStatus;
import com.gte619n.healthfitness.core.nutrition.MealType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
    Instant createdAt,
    // "Remove Leftovers" state (IMPL-LEFTOVER-01); null when there is none.
    LeftoverDto leftover
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
            e.createdAt(),
            leftoverDtoOf(e.leftover())
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

    /**
     * "Remove Leftovers" state for the clients (IMPL-LEFTOVER-01). Carries the
     * as-served baseline (so an APPLIED entry can show "Served → Ate") and, while
     * {@code PENDING_REVIEW}, the pending proposal for the review diff (spec D7).
     */
    public record LeftoverDto(
        LeftoverStatus status,
        MacrosDto servedMacros,
        List<LeftoverIngredientDto> servedIngredients,
        LeftoverProposalDto proposal
    ) {}

    /** One as-served ingredient baseline (name + grams + macros). */
    public record LeftoverIngredientDto(
        String name,
        Double servingGrams,
        Double quantity,
        MacrosDto macros
    ) {}

    /** The pending leftover proposal shown in the review diff. */
    public record LeftoverProposalDto(
        List<LeftoverItemDto> items,
        MacrosDto servedTotals,
        MacrosDto consumedTotals,
        double overallConfidence,
        boolean warning,
        String warningNote
    ) {}

    /** One item within the pending leftover proposal. */
    public record LeftoverItemDto(
        String name,
        Double servedGrams,
        Double consumedGrams,
        Double remainingGrams,
        boolean matched,
        MacrosDto consumedMacros
    ) {}

    private static LeftoverDto leftoverDtoOf(Leftover l) {
        if (l == null) {
            return null;
        }
        List<LeftoverIngredientDto> served = new ArrayList<>();
        if (l.servedIngredients() != null) {
            for (CompositeIngredient ing : l.servedIngredients()) {
                served.add(new LeftoverIngredientDto(
                    ing.name(), ing.servingGrams(), ing.quantity(), MacrosDto.from(ing.macros())));
            }
        }
        return new LeftoverDto(
            l.status(), MacrosDto.from(l.servedMacros()), served, proposalDtoOf(l.proposal()));
    }

    private static LeftoverProposalDto proposalDtoOf(LeftoverProposal p) {
        if (p == null) {
            return null;
        }
        List<LeftoverItemDto> items = new ArrayList<>();
        if (p.items() != null) {
            for (LeftoverProposal.Item it : p.items()) {
                items.add(new LeftoverItemDto(
                    it.name(), it.servedGrams(), it.consumedGrams(), it.remainingGrams(),
                    it.matched(), MacrosDto.from(it.consumedMacros())));
            }
        }
        return new LeftoverProposalDto(
            items, MacrosDto.from(p.servedTotals()), MacrosDto.from(p.consumedTotals()),
            p.overallConfidence(), p.warning(), p.warningNote());
    }
}
