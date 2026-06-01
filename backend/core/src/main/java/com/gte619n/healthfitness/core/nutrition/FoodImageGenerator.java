package com.gte619n.healthfitness.core.nutrition;

import java.util.Optional;

/**
 * Port for generating a house-style studio image of a catalog food (IMPL-13
 * Milestone 4). Defined in {@code core} so {@link FoodImageService} can request
 * generation without {@code core} depending on the Gemini SDK; the concrete
 * implementation ({@code GeminiFoodImageGenerator}, model
 * {@code gemini-3.1-flash-image-preview}) lives in {@code integrations} and is
 * injected via {@code ObjectProvider}, mirroring the {@link BarcodeLookup} /
 * {@link MealPhotoStore} seams.
 *
 * <p>Implementations never throw on a generation failure: a graceful empty is
 * returned so the orchestration can mark the food {@code FAILED} and move on.
 */
public interface FoodImageGenerator {

    /**
     * Generate a plated-food studio image for a catalog food.
     *
     * @param food            the catalog food to depict
     * @param referencePhoto  the user's real meal photo bytes used as a visual
     *                        reference, or {@code null} to generate from the
     *                        food name/category alone
     * @param referenceMime   the reference photo's content type (e.g.
     *                        {@code image/jpeg}); ignored when
     *                        {@code referencePhoto} is {@code null}
     * @return the generated image as PNG bytes, or empty if generation failed
     */
    Optional<byte[]> generate(CatalogFood food, byte[] referencePhoto, String referenceMime);
}
