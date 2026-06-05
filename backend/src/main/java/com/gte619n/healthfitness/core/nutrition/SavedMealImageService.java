package com.gte619n.healthfitness.core.nutrition;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Generates the AI plated-dish studio image for a {@link SavedMeal}, the
 * reusable thumbnail shown wherever the meal is surfaced (search, reuse). Mirrors
 * {@link FoodImageService} but persists the result onto the saved meal rather
 * than a catalog food, and generates text-only (a described meal has no capture
 * photo to use as a visual reference).
 *
 * <p>The finished meal is a plated dish, so it reuses the existing
 * {@link FoodImageGenerator} (fed a transient subject named after the meal) and
 * {@link FoodImageStore}. When those ports are unavailable (images disabled /
 * core-only test context) enqueueing is a graceful no-op and the meal stays
 * {@code NONE}.
 */
@Service
public class SavedMealImageService {

    private static final int BACKFILL_LIMIT = 500;

    private final SavedMealRepository repository;
    private final ObjectProvider<FoodImageGenerator> generator;
    private final ObjectProvider<FoodImageStore> store;

    public SavedMealImageService(
        SavedMealRepository repository,
        ObjectProvider<FoodImageGenerator> generator,
        ObjectProvider<FoodImageStore> store
    ) {
        this.repository = repository;
        this.generator = generator;
        this.store = store;
    }

    /**
     * Enqueue async studio-image generation for a saved meal. Flips it to
     * {@code PENDING} synchronously (so the create call reflects it), then runs
     * off-thread. A no-op when the generator or storage port is unavailable.
     */
    public void enqueueGeneration(String mealId) {
        if (mealId == null || mealId.isBlank()) {
            return;
        }
        if (generator.getIfAvailable() == null || store.getIfAvailable() == null) {
            return;
        }
        markStatus(mealId, FoodImageStatus.PENDING, null);
        CompletableFuture.runAsync(() -> generateNow(mealId));
    }

    /** Synchronous generation; walks the meal to READY or FAILED. Never throws. */
    public void generateNow(String mealId) {
        FoodImageGenerator gen = generator.getIfAvailable();
        FoodImageStore storage = store.getIfAvailable();
        if (gen == null || storage == null) {
            return;
        }
        Optional<SavedMeal> found = repository.findById(mealId);
        if (found.isEmpty()) {
            return;
        }
        SavedMeal meal = found.get();
        try {
            Optional<byte[]> image = gen.generate(mealSubject(meal), null, null);
            if (image.isEmpty() || image.get().length == 0) {
                markStatus(mealId, FoodImageStatus.FAILED, null);
                return;
            }
            String url = storage.upload(mealId, image.get());
            markStatus(mealId, FoodImageStatus.READY, url);
        } catch (RuntimeException e) {
            System.err.println(
                "Saved meal image generation failed for " + mealId + ": " + e.getMessage());
            markStatus(mealId, FoodImageStatus.FAILED, null);
        }
    }

    /** Enqueue generation for every saved meal still at {@code NONE}. */
    public int backfillMissing() {
        if (generator.getIfAvailable() == null || store.getIfAvailable() == null) {
            return 0;
        }
        List<SavedMeal> pending = repository.findByImageStatus(FoodImageStatus.NONE, BACKFILL_LIMIT);
        for (SavedMeal meal : pending) {
            enqueueGeneration(meal.mealId());
        }
        return pending.size();
    }

    /** A transient catalog-food subject so the plated-dish generator has a name. */
    private static CatalogFood mealSubject(SavedMeal meal) {
        String name = (meal.name() == null || meal.name().isBlank()) ? "a plated meal" : meal.name();
        return new CatalogFood(
            meal.mealId(), name, name.toLowerCase(), null, null, null, null, List.of(), 0,
            FoodSource.GEMINI_DESCRIPTION, null, FoodStatus.UNVERIFIED, 0, null, null,
            FoodImageStatus.PENDING, null, null, null);
    }

    private void markStatus(String mealId, FoodImageStatus status, String url) {
        repository.findById(mealId).ifPresent(meal -> repository.save(meal.withImage(url, status)));
    }
}
