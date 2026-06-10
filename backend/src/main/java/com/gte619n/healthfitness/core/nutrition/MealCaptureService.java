package com.gte619n.healthfitness.core.nutrition;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Captures a meal/product photo and logs it <strong>asynchronously</strong>.
 *
 * <p>{@link #captureMeal} stores the photo, creates an {@code ANALYZING}
 * placeholder entry and returns immediately, then runs the (slow) Gemini
 * analysis off the request thread. When the analysis returns the placeholder is
 * filled in:
 * <ul>
 *   <li>a <em>single packaged product</em> (a protein shake, a tub of yogurt)
 *       becomes one catalog food whose studio image is the product itself, and
 *   <li>a <em>prepared meal</em> becomes a composite entry — one ingredient per
 *       component (each with its own image) plus a finished-meal image — named
 *       with a short natural dish name from the model.
 * </ul>
 * The clients poll the day view, so the placeholder swaps to the finished entry
 * once the work completes.
 *
 * <p>Lives in {@code core} and depends on the {@link MealPhotoAnalyzer} and
 * {@link MealPhotoStore} <em>ports</em> via {@link ObjectProvider} so core unit
 * tests can construct it without the integrations beans.
 */
@Service
public class MealCaptureService {

    private final ObjectProvider<MealPhotoAnalyzer> mealAnalyzer;
    private final ObjectProvider<MealPhotoStore> photoStore;
    private final FoodCatalogService catalog;
    private final NutritionService nutrition;
    private final FoodEntryImageService foodEntryImages;
    private final SyncChangeNotifier syncNotifier;

    public MealCaptureService(
        ObjectProvider<MealPhotoAnalyzer> mealAnalyzer,
        ObjectProvider<MealPhotoStore> photoStore,
        FoodCatalogService catalog,
        NutritionService nutrition,
        FoodEntryImageService foodEntryImages,
        SyncChangeNotifier syncNotifier
    ) {
        this.mealAnalyzer = mealAnalyzer;
        this.photoStore = photoStore;
        this.catalog = catalog;
        this.nutrition = nutrition;
        this.foodEntryImages = foodEntryImages;
        this.syncNotifier = syncNotifier;
    }

    /**
     * Store the photo, log an {@code ANALYZING} placeholder and kick the analysis
     * off-thread. Returns the placeholder entry so the caller can respond
     * immediately; the entry is filled in later via the day view.
     */
    public FoodEntry captureMeal(
        String userId, LocalDate date, MealType meal, byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("photo is required");
        }
        MealPhotoAnalyzer analyzer = mealAnalyzer.getIfAvailable();
        if (analyzer == null) {
            throw new IllegalStateException("meal photo analysis is not available");
        }
        // Dedupe re-uploads of the same image. The user logs each photo as its
        // own capture; if they upload an identical image again we silently return
        // the entry the first upload created rather than storing the photo a
        // second time, logging a duplicate placeholder and paying for another
        // Gemini analysis. Hash the original bytes (pre-normalization) so it's
        // stable. Failed captures don't dedupe — re-uploading retries them.
        String contentHash = sha256Hex(imageBytes);
        Optional<FoodEntry> duplicate = nutrition.findActivePhotoByHash(userId, date, contentHash);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        String photoRef = storePhoto(userId, imageBytes, mimeType);
        FoodEntry placeholder = nutrition.beginAnalyzingEntry(userId, date, meal, photoRef, contentHash);
        CompletableFuture.runAsync(() -> analyzeAndFinalize(
            userId, date, placeholder.entryId(), imageBytes, mimeType, photoRef, analyzer));
        return placeholder;
    }

    /**
     * Run the analysis and finalize the placeholder. Package-private and
     * synchronous so tests can drive it without the async hop. Never throws — a
     * failure marks the entry {@code FAILED}.
     */
    void analyzeAndFinalize(
        String userId, LocalDate date, String entryId,
        byte[] imageBytes, String mimeType, String photoRef, MealPhotoAnalyzer analyzer) {
        try {
            MealPhotoAnalyzer.MealAnalysis analysis = analyzer.analyzeMeal(imageBytes, mimeType);
            List<MealPhotoAnalyzer.MealItem> items = new ArrayList<>();
            for (MealPhotoAnalyzer.MealItem item : analysis.items()) {
                if (item != null && item.name() != null && !item.name().isBlank()) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                nutrition.markAnalysisFailed(userId, date, entryId);
                return;
            }
            if (analysis.packagedProduct()) {
                finalizeProduct(userId, date, entryId, analysis, items.get(0), photoRef);
            } else {
                finalizeMeal(userId, date, entryId, analysis, items, photoRef);
            }
        } catch (RuntimeException e) {
            System.err.println(
                "Meal capture analysis failed for entry " + entryId + ": " + e.getMessage());
            nutrition.markAnalysisFailed(userId, date, entryId);
        } finally {
            // The placeholder transitioned (READY/FAILED) — wake the user's
            // devices to pull the updated entry. origin=null: the analysis ran
            // server-side, so even the capturing device should refresh.
            syncNotifier.changed(userId, null, "nutritionDays/entries");
        }
    }

    /** A single packaged product: one catalog food + a product studio image. */
    private void finalizeProduct(
        String userId, LocalDate date, String entryId,
        MealPhotoAnalyzer.MealAnalysis analysis, MealPhotoAnalyzer.MealItem item, String photoRef) {
        String name = firstNonBlank(analysis.mealName(), item.name(), "Product");
        String brand = analysis.brand();
        double grams = item.estimatedPortionGrams() != null && item.estimatedPortionGrams() > 0
            ? item.estimatedPortionGrams() : 100.0;
        Macros per100g = item.macrosPer100g();
        Macros portion = per100g != null ? per100g.scale(grams / 100.0) : Macros.zero();
        String label = gramsLabel(grams);
        // Repeat captures of the same exact product reuse the existing catalog
        // food (and its studio image). Otherwise create one: the "product"
        // category makes the generator render the exact branded product itself,
        // with the capture photo riding along as the visual reference.
        CatalogFood food = catalog.findProduct(name, brand).orElseGet(() -> catalog.create(
            userId, name, brand, null, "product", per100g,
            List.of(new ServingSize(label, grams)), 0, FoodSource.GEMINI_PHOTO, photoRef));
        nutrition.finalizeSingleFood(
            userId, date, entryId, food.foodId(), name, label, grams, 1.0, portion);
    }

    /** A prepared meal: one ingredient food per component + a finished-meal image. */
    private void finalizeMeal(
        String userId, LocalDate date, String entryId,
        MealPhotoAnalyzer.MealAnalysis analysis, List<MealPhotoAnalyzer.MealItem> items, String photoRef) {
        String mealName = firstNonBlank(analysis.mealName(), composeMealName(items), "Meal");
        List<CompositeIngredient> ingredients = new ArrayList<>();
        for (MealPhotoAnalyzer.MealItem item : items) {
            double grams = item.estimatedPortionGrams() != null ? item.estimatedPortionGrams() : 0.0;
            Macros per100g = item.macrosPer100g();
            Macros portion = per100g != null ? per100g.scale((grams > 0 ? grams : 0.0) / 100.0) : Macros.zero();
            String label = gramsLabel(grams);
            CatalogFood food = catalog.create(
                userId, item.name(), null, null, "ingredient", per100g,
                List.of(new ServingSize(label, grams > 0 ? grams : 100.0)), 0,
                FoodSource.GEMINI_PHOTO, null);
            ingredients.add(new CompositeIngredient(
                item.name(), food.foodId(), per100g, grams, label, 1.0, portion));
        }
        nutrition.finalizeCompositeMeal(userId, date, entryId, mealName, ingredients);
        foodEntryImages.enqueueGeneration(userId, date, entryId, mealName, photoRef);
    }

    // ---- helpers ----

    private String storePhoto(String userId, byte[] imageBytes, String mimeType) {
        MealPhotoStore store = photoStore.getIfAvailable();
        return store == null ? null : store.store(userId, imageBytes, mimeType);
    }

    /** SHA-256 hex of the raw photo bytes — the dedupe key for re-uploads. */
    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK spec.
            throw new IllegalStateException(e);
        }
    }

    private static String gramsLabel(double grams) {
        return Math.round(grams > 0 ? grams : 100.0) + " g";
    }

    /** Fallback name from the components: "Salmon", "Salmon & Rice", etc. */
    private static String composeMealName(List<MealPhotoAnalyzer.MealItem> items) {
        List<String> names = items.stream().map(MealPhotoAnalyzer.MealItem::name).toList();
        return switch (names.size()) {
            case 0 -> "Meal";
            case 1 -> names.get(0);
            case 2 -> names.get(0) + " & " + names.get(1);
            default -> String.join(", ", names.subList(0, names.size() - 1))
                + " & " + names.get(names.size() - 1);
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
