package com.gte619n.healthfitness.core.nutrition;

/**
 * Builds the content cache key used to reuse an already-generated studio image
 * across foods/meals whose visual subject is identical (IMPL-13 M4 image reuse).
 * Two subjects that normalize to the same key produce the same Gemini prompt
 * (same style bucket + name + brand), so the first generated image can be reused
 * instead of paying to regenerate an identical one — the single biggest lever on
 * image-generation spend, since the catalog only partly dedupes foods (barcode
 * and exact product name), so the same "grilled chicken" is otherwise generated
 * again for every user who logs it.
 *
 * <p>Only meaningful for name-only generation: when a user's meal photo is fed as
 * a visual reference the image is unique to that capture, so callers skip the
 * cache for reference-based generation.
 */
public final class FoodImageCacheKey {

    /**
     * Bump when the generator's prompt/style or the image model changes in a way
     * that should invalidate previously cached images (opens a new key namespace
     * so stale images aren't reused).
     */
    private static final String VERSION = "v1";

    private FoodImageCacheKey() {}

    /**
     * Key a food/meal subject by the fields that drive the generated image:
     * category (selects the raw-ingredient / packaged-product / plated-dish
     * style), name and brand. Case- and whitespace-insensitive.
     */
    public static String of(CatalogFood subject) {
        if (subject == null) {
            return VERSION + "|||";
        }
        return VERSION
            + "|" + norm(subject.category())
            + "|" + norm(subject.name())
            + "|" + norm(subject.brand());
    }

    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
