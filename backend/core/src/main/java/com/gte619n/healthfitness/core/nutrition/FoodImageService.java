package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Orchestrates AI <strong>studio-image</strong> generation for catalog foods
 * (IMPL-13 Milestone 4). One image is generated per unique catalog food,
 * asynchronously, then reused forever.
 *
 * <p>Lifecycle: a food starts {@code imageStatus = NONE}. When generation is
 * enqueued the food is flipped to {@code PENDING} and persisted (so
 * {@code POST /api/foods} returns immediately while the work runs on a
 * background thread). On success {@code imageUrl} is set and status becomes
 * {@code READY}; on any failure (generator returns empty, or it/storage throws)
 * the food is marked {@code FAILED}.
 *
 * <p>Generation, storage and reference-photo-read are {@code core} ports
 * implemented in {@code integrations} ({@link FoodImageGenerator},
 * {@link FoodImageStore}, {@link MealPhotoReader}) and injected via
 * {@link ObjectProvider} — the same seam M2's {@link BarcodeLookup} and M3's
 * {@link MealPhotoStore} use, so {@code core} unit tests run without the
 * integrations beans. When the generator or store bean is absent the enqueue is
 * a graceful no-op (the food stays {@code NONE}).
 *
 * <p>Async mechanism mirrors {@code DrugCatalogService}'s
 * {@code CompletableFuture.runAsync}; no {@code @Async}/{@code @EnableAsync} is
 * used anywhere in this codebase.
 */
@Service
public class FoodImageService {

    private static final int BACKFILL_LIMIT = 500;

    private final FoodCatalogRepository repository;
    private final ObjectProvider<FoodImageGenerator> generator;
    private final ObjectProvider<FoodImageStore> store;
    private final ObjectProvider<MealPhotoReader> photoReader;

    public FoodImageService(
        FoodCatalogRepository repository,
        ObjectProvider<FoodImageGenerator> generator,
        ObjectProvider<FoodImageStore> store,
        ObjectProvider<MealPhotoReader> photoReader
    ) {
        this.repository = repository;
        this.generator = generator;
        this.store = store;
        this.photoReader = photoReader;
    }

    /**
     * Enqueue async studio-image generation for a newly created food, reading
     * the user's meal photo (if any) as a visual reference. Returns immediately;
     * the actual work runs on a background thread. A no-op when the generator or
     * storage port is unavailable (e.g. images disabled or core-only context).
     *
     * @param foodId          the food to generate for
     * @param referencePhotoRef optional meal-photo reference (public URL) to use
     *                          as a visual reference, or {@code null}
     */
    public void enqueueGeneration(String foodId, String referencePhotoRef) {
        if (foodId == null || foodId.isBlank()) {
            return;
        }
        if (generator.getIfAvailable() == null || store.getIfAvailable() == null) {
            // No live image pipeline (disabled, or core-only test context).
            return;
        }
        // Flip to PENDING synchronously so the create response reflects it, then
        // run generation off the request thread.
        markStatus(foodId, FoodImageStatus.PENDING, null);
        CompletableFuture.runAsync(() -> generateNow(foodId, referencePhotoRef));
    }

    /**
     * Run generation synchronously (used by the background task and by the
     * backfill job). Resolves the food, generates + uploads the image, and walks
     * the status to {@code READY} or {@code FAILED}. Never throws.
     */
    public void generateNow(String foodId, String referencePhotoRef) {
        FoodImageGenerator gen = generator.getIfAvailable();
        FoodImageStore storage = store.getIfAvailable();
        if (gen == null || storage == null) {
            return;
        }
        Optional<CatalogFood> found = repository.findById(foodId);
        if (found.isEmpty()) {
            return;
        }
        CatalogFood food = found.get();
        try {
            MealPhotoReader.Photo reference = loadReference(referencePhotoRef);
            byte[] refBytes = reference == null ? null : reference.bytes();
            String refMime = reference == null ? null : reference.mimeType();

            Optional<byte[]> image = gen.generate(food, refBytes, refMime);
            if (image.isEmpty() || image.get().length == 0) {
                markStatus(foodId, FoodImageStatus.FAILED, null);
                return;
            }
            String url = storage.upload(foodId, image.get());
            markStatus(foodId, FoodImageStatus.READY, url);
        } catch (RuntimeException e) {
            System.err.println(
                "Food studio image generation failed for " + foodId + ": " + e.getMessage());
            markStatus(foodId, FoodImageStatus.FAILED, null);
        }
    }

    /**
     * Enqueue generation for every food still at {@code imageStatus = NONE}
     * (backfill of already-seeded foods). Returns the number enqueued.
     */
    public int backfillMissing() {
        if (generator.getIfAvailable() == null || store.getIfAvailable() == null) {
            return 0;
        }
        List<CatalogFood> pending = repository.findByImageStatus(FoodImageStatus.NONE, BACKFILL_LIMIT);
        for (CatalogFood food : pending) {
            enqueueGeneration(food.foodId(), null);
        }
        return pending.size();
    }

    // ---- helpers ----

    private MealPhotoReader.Photo loadReference(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        MealPhotoReader reader = photoReader.getIfAvailable();
        if (reader == null) {
            return null;
        }
        return reader.read(ref).orElse(null);
    }

    /** Re-read the food and persist a new image status (+ optional url). */
    private void markStatus(String foodId, FoodImageStatus status, String imageUrl) {
        repository.findById(foodId).ifPresent(food -> {
            CatalogFood updated = new CatalogFood(
                food.foodId(),
                food.name(),
                food.nameLower(),
                food.brand(),
                food.barcode(),
                food.category(),
                food.macrosPer100g(),
                food.servingSizes(),
                food.defaultServingIndex(),
                food.source(),
                food.sourceRef(),
                food.status(),
                food.confirmationCount(),
                food.verifiedAt(),
                imageUrl != null ? imageUrl : food.imageUrl(),
                status,
                food.createdBy(),
                food.createdAt(),
                Instant.now()
            );
            repository.save(updated);
        });
    }
}
