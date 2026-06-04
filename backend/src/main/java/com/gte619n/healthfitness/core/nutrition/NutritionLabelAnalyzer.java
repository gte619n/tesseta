package com.gte619n.healthfitness.core.nutrition;

/**
 * Port for OCR-ing a packaged nutrition-label photo into a structured food.
 *
 * <p>Defined in {@code core} so {@link NutritionCaptureService} can depend on
 * the abstraction without pulling {@code integrations} into {@code core}. The
 * concrete implementation ({@code NutritionLabelExtractor},
 * {@code gemini-3.5-flash} tool calling) lives in {@code integrations} and is
 * injected via {@code ObjectProvider}, mirroring the {@link BarcodeLookup} seam.
 *
 * <p>The implementation normalizes the label's per-serving panel to
 * <strong>per-100 g</strong> before returning, so the result maps directly onto
 * {@link CatalogFood#macrosPer100g()}. On an extraction failure it throws, which
 * the controller maps to a 422.
 */
public interface NutritionLabelAnalyzer {

    /**
     * Read the nutrition label and return a packaged-food proposal.
     *
     * @param imageBytes the raw label photo
     * @param mimeType   the image content type (e.g. {@code image/jpeg})
     * @return the extracted, per-100 g-normalized label
     */
    LabelExtraction analyze(byte[] imageBytes, String mimeType);

    /**
     * Structured result of a label OCR, already normalized to per-100 g.
     *
     * @param productName           product name as printed (nullable)
     * @param brand                 brand/manufacturer as printed (nullable)
     * @param servingSizeGrams      grams in one serving, as printed (nullable)
     * @param servingsPerContainer  servings per container, as printed (nullable)
     * @param macrosPer100g         macros normalized to per 100 g
     */
    record LabelExtraction(
        String productName,
        String brand,
        Double servingSizeGrams,
        Double servingsPerContainer,
        Macros macrosPer100g
    ) {}
}
