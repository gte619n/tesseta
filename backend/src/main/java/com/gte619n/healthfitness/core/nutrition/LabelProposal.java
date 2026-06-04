package com.gte619n.healthfitness.core.nutrition;

import java.util.List;

/**
 * Result of analyzing a packaged nutrition-label photo: the stored photo
 * reference plus a proposed {@link CatalogFood}-shaped draft. This is a
 * <strong>proposal only</strong> — nothing is persisted. The client reviews and
 * saves it via {@code POST /api/foods}.
 *
 * <p>Macros are already normalized to per-100 g so the draft maps directly onto
 * {@link CatalogFood#macrosPer100g()}. The draft carries
 * {@code source = GEMINI_LABEL} and {@code status = UNVERIFIED}.
 *
 * @param photoRef storage reference for the uploaded label photo (nullable)
 * @param food     the proposed packaged-food draft
 */
public record LabelProposal(String photoRef, FoodDraft food) {

    /**
     * A proposed catalog food derived from a label, not yet persisted.
     *
     * @param name                product name (nullable; client may edit)
     * @param brand               brand/manufacturer (nullable)
     * @param barcode             scanned barcode this label is attached to (nullable)
     * @param macrosPer100g       macros normalized to per 100 g
     * @param servingSizes        proposed serving sizes (e.g. the label serving)
     * @param defaultServingIndex index into {@code servingSizes} for the default
     * @param source              always {@link FoodSource#GEMINI_LABEL}
     * @param status              always {@link FoodStatus#UNVERIFIED}
     */
    public record FoodDraft(
        String name,
        String brand,
        String barcode,
        Macros macrosPer100g,
        List<ServingSize> servingSizes,
        int defaultServingIndex,
        FoodSource source,
        FoodStatus status
    ) {}
}
