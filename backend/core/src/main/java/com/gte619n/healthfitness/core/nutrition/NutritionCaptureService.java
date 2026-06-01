package com.gte619n.healthfitness.core.nutrition;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the AI capture flows (IMPL-13 Milestone 3): meal-photo
 * itemization and nutrition-label OCR. Stores the raw photo, runs Gemini
 * extraction, and (for meals) tries to match each item to an existing catalog
 * food.
 *
 * <p>These methods return <strong>proposals only</strong> — they never persist
 * entries or catalog foods. Saving is the client's job via the existing M1
 * endpoints ({@code POST /api/foods},
 * {@code POST /api/me/nutrition/{date}/entries},
 * {@code POST /api/foods/{id}/confirm}).
 *
 * <p>Lives in {@code core} and depends on the {@link MealPhotoAnalyzer},
 * {@link NutritionLabelAnalyzer} and {@link MealPhotoStore} <em>ports</em>
 * (implemented in {@code integrations}), injected via {@link ObjectProvider} so
 * core unit tests construct it without the integrations beans — the same seam
 * M2 used for {@link BarcodeLookup}. When an analyzer bean is absent the service
 * raises {@link IllegalStateException}; the photo store is optional and simply
 * yields a null {@code photoRef} when unavailable.
 */
@Service
public class NutritionCaptureService {

    private final ObjectProvider<MealPhotoAnalyzer> mealAnalyzer;
    private final ObjectProvider<NutritionLabelAnalyzer> labelAnalyzer;
    private final ObjectProvider<MealPhotoStore> photoStore;
    private final FoodCatalogService catalog;

    public NutritionCaptureService(
        ObjectProvider<MealPhotoAnalyzer> mealAnalyzer,
        ObjectProvider<NutritionLabelAnalyzer> labelAnalyzer,
        ObjectProvider<MealPhotoStore> photoStore,
        FoodCatalogService catalog
    ) {
        this.mealAnalyzer = mealAnalyzer;
        this.labelAnalyzer = labelAnalyzer;
        this.photoStore = photoStore;
        this.catalog = catalog;
    }

    /**
     * Store the meal photo and itemize it into proposed foods, matching each to
     * an existing catalog food by name where possible. Returns a proposal; saves
     * nothing.
     */
    public MealProposal analyzeMeal(String userId, byte[] imageBytes, String mimeType) {
        requirePhoto(imageBytes);
        MealPhotoAnalyzer analyzer = mealAnalyzer.getIfAvailable();
        if (analyzer == null) {
            throw new IllegalStateException("meal photo analysis is not available");
        }

        String photoRef = storePhoto(userId, imageBytes, mimeType);
        List<MealPhotoAnalyzer.MealItem> items = analyzer.analyze(imageBytes, mimeType);

        List<MealProposal.MealProposalItem> proposed = new ArrayList<>();
        for (MealPhotoAnalyzer.MealItem item : items) {
            if (item == null) continue;
            proposed.add(new MealProposal.MealProposalItem(
                item.name(),
                item.estimatedPortionGrams(),
                item.macrosPer100g(),
                item.confidence(),
                matchCatalogFoodId(item.name())
            ));
        }
        return new MealProposal(photoRef, proposed);
    }

    /**
     * Store the label photo and OCR it into a proposed packaged catalog food
     * (per-100 g, {@code GEMINI_LABEL}, {@code UNVERIFIED}), attaching the
     * barcode when one was scanned. Returns a proposal; saves nothing.
     */
    public LabelProposal analyzeLabel(
        String userId, byte[] imageBytes, String mimeType, String barcode) {
        requirePhoto(imageBytes);
        NutritionLabelAnalyzer analyzer = labelAnalyzer.getIfAvailable();
        if (analyzer == null) {
            throw new IllegalStateException("nutrition label analysis is not available");
        }

        String photoRef = storePhoto(userId, imageBytes, mimeType);
        NutritionLabelAnalyzer.LabelExtraction label = analyzer.analyze(imageBytes, mimeType);

        List<ServingSize> servingSizes = new ArrayList<>();
        if (label.servingSizeGrams() != null && label.servingSizeGrams() > 0) {
            servingSizes.add(new ServingSize("1 serving", label.servingSizeGrams()));
        }
        servingSizes.add(new ServingSize("100 g", 100.0));

        LabelProposal.FoodDraft draft = new LabelProposal.FoodDraft(
            label.productName(),
            label.brand(),
            (barcode != null && !barcode.isBlank()) ? barcode : null,
            label.macrosPer100g(),
            servingSizes,
            0,
            FoodSource.GEMINI_LABEL,
            FoodStatus.UNVERIFIED
        );
        return new LabelProposal(photoRef, draft);
    }

    // ---- helpers ----

    private static void requirePhoto(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("photo is required");
        }
    }

    private String storePhoto(String userId, byte[] imageBytes, String mimeType) {
        MealPhotoStore store = photoStore.getIfAvailable();
        return store == null ? null : store.store(userId, imageBytes, mimeType);
    }

    /** Top catalog hit by name, or null on miss. */
    private String matchCatalogFoodId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        List<CatalogFood> hits = catalog.search(name);
        return hits.isEmpty() ? null : hits.get(0).foodId();
    }
}
