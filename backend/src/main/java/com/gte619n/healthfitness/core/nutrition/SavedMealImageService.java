package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobException;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobQueue;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SavedMealImageService.class);

    private static final int BACKFILL_LIMIT = 500;

    private final SavedMealRepository repository;
    private final ObjectProvider<FoodImageGenerator> generator;
    private final ObjectProvider<FoodImageStore> store;
    private final ObjectProvider<NutritionJobQueue> jobQueue;

    public SavedMealImageService(
        SavedMealRepository repository,
        ObjectProvider<FoodImageGenerator> generator,
        ObjectProvider<FoodImageStore> store,
        ObjectProvider<NutritionJobQueue> jobQueue
    ) {
        this.repository = repository;
        this.generator = generator;
        this.store = store;
        this.jobQueue = jobQueue;
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
        NutritionJobQueue queue = jobQueue.getIfAvailable();
        if (queue != null) {
            queue.enqueue(NutritionJob.savedMealImage(mealId));
        } else {
            CompletableFuture.runAsync(() -> generateNow(mealId));
        }
    }

    /**
     * Single-attempt generation (backfill / local executor): walks the meal to
     * {@code READY} or {@code FAILED}. Never throws.
     */
    public void generateNow(String mealId) {
        try {
            generateOrThrow(mealId);
        } catch (RuntimeException e) {
            log.warn("Saved meal image generation failed for {}: {}", mealId, e.getMessage());
            markFailed(mealId);
        }
    }

    /**
     * Generate + upload the plated-dish image, marking the meal {@code READY} on
     * success. Throws {@link NutritionJobException} when generation produced
     * nothing so the durable queue can retry. Idempotent: a redelivered job whose
     * image already landed (or whose name is cached) completes without another
     * Gemini call.
     */
    public void generateOrThrow(String mealId) {
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
        // Idempotent: a redelivered job whose image already landed is a no-op.
        if (meal.imageStatus() == FoodImageStatus.READY && meal.imageUrl() != null) {
            return;
        }

        // Described meals are plated dishes generated from the name alone, so
        // meals with the same name reuse one image instead of regenerating.
        CatalogFood subject = mealSubject(meal);
        String cacheKey = FoodImageCacheKey.of(subject);
        Optional<String> cached = storage.findCachedUrl(cacheKey);
        if (cached.isPresent()) {
            markStatus(mealId, FoodImageStatus.READY, cached.get());
            return;
        }

        Optional<byte[]> image = gen.generate(subject, null, null);
        if (image.isEmpty() || image.get().length == 0) {
            throw new NutritionJobException("saved meal image generation returned no image for " + mealId);
        }
        String url = storage.upload(mealId, image.get());
        storage.putCachedUrl(cacheKey, url);
        markStatus(mealId, FoodImageStatus.READY, url);
    }

    /** Record a terminal image-generation failure (queue retries exhausted). */
    public void markFailed(String mealId) {
        markStatus(mealId, FoodImageStatus.FAILED, null);
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
