package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobException;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(FoodImageService.class);

    private static final int BACKFILL_LIMIT = 500;

    // A catalog image PENDING past this age is presumed orphaned — its bare
    // runAsync task died with the instance (OOM/restart/deploy) and will never
    // finish — so a day read re-enqueues it. The single-food entry that
    // references the food shows a spinner/placeholder that would otherwise never
    // resolve. Kept well above a normal generation time so a healthy in-flight
    // job is never disturbed.
    private static final Duration PENDING_STALE_AFTER = Duration.ofMinutes(3);
    private static final int STALE_PENDING_LIMIT = 50;

    private final FoodCatalogRepository repository;
    private final ObjectProvider<FoodImageGenerator> generator;
    private final ObjectProvider<FoodImageStore> store;
    private final ObjectProvider<MealPhotoReader> photoReader;
    private final ObjectProvider<NutritionJobQueue> jobQueue;

    public FoodImageService(
        FoodCatalogRepository repository,
        ObjectProvider<FoodImageGenerator> generator,
        ObjectProvider<FoodImageStore> store,
        ObjectProvider<MealPhotoReader> photoReader,
        ObjectProvider<NutritionJobQueue> jobQueue
    ) {
        this.repository = repository;
        this.generator = generator;
        this.store = store;
        this.photoReader = photoReader;
        this.jobQueue = jobQueue;
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
        // hand the work to the durable queue (or, with no queue bean, run it off
        // the request thread as before).
        markStatus(foodId, FoodImageStatus.PENDING, null);
        NutritionJobQueue queue = jobQueue.getIfAvailable();
        if (queue != null) {
            queue.enqueue(NutritionJob.foodImage(foodId, referencePhotoRef));
        } else {
            CompletableFuture.runAsync(() -> generateNow(foodId, referencePhotoRef));
        }
    }

    /**
     * Run generation synchronously and walk the food to {@code READY} or
     * {@code FAILED} — the single-attempt entry point used by the backfill job and
     * the local executor. Never throws; a failure is recorded as {@code FAILED}.
     */
    public void generateNow(String foodId, String referencePhotoRef) {
        try {
            generateOrThrow(foodId, referencePhotoRef);
        } catch (RuntimeException e) {
            log.warn("Food studio image generation failed for {}: {}", foodId, e.getMessage());
            markFailed(foodId);
        }
    }

    /**
     * Generate + upload the image, marking the food {@code READY} on success.
     * Throws {@link NutritionJobException} when generation produced nothing (or an
     * upstream call errored) so the durable queue can retry. Idempotent: a
     * redelivered job whose image already landed, or whose subject is already in
     * the shared cache, completes without another Gemini call.
     */
    public void generateOrThrow(String foodId, String referencePhotoRef) {
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
        // Idempotent: a redelivered job whose image already landed is a no-op.
        if (food.imageStatus() == FoodImageStatus.READY && food.imageUrl() != null) {
            return;
        }

        MealPhotoReader.Photo reference = loadReference(referencePhotoRef);
        byte[] refBytes = reference == null ? null : reference.bytes();
        String refMime = reference == null ? null : reference.mimeType();

        // Name-only images (no user photo) are identical for the same subject,
        // so reuse an already-generated one instead of paying Gemini again.
        // Reference-based images depend on the user's capture, so skip the cache.
        boolean cacheable = refBytes == null || refBytes.length == 0;
        String cacheKey = cacheable ? FoodImageCacheKey.of(food) : null;
        if (cacheable) {
            Optional<String> cached = storage.findCachedUrl(cacheKey);
            if (cached.isPresent()) {
                markStatus(foodId, FoodImageStatus.READY, cached.get());
                return;
            }
        }

        Optional<byte[]> image = gen.generate(food, refBytes, refMime);
        if (image.isEmpty() || image.get().length == 0) {
            throw new NutritionJobException("food image generation returned no image for " + foodId);
        }
        String url = storage.upload(foodId, image.get());
        if (cacheable) {
            storage.putCachedUrl(cacheKey, url);
        }
        markStatus(foodId, FoodImageStatus.READY, url);
    }

    /** Record a terminal image-generation failure (queue retries exhausted). */
    public void markFailed(String foodId) {
        markStatus(foodId, FoodImageStatus.FAILED, null);
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

    /**
     * Self-heal orphaned studio-image generation: re-enqueue any catalog food
     * stuck at {@code PENDING} longer than {@link #PENDING_STALE_AFTER}.
     * Generation runs in a bare {@code runAsync} with no retry, so an instance
     * OOM/restart/deploy mid-generation leaves the food PENDING forever — and the
     * single-food entry that references it never learns the image finished
     * (offline it shows a permanent placeholder). Re-enqueuing re-stamps the
     * food's {@code updatedAt}, so a healthy in-flight job or a just-swept one is
     * not re-swept until the window elapses again. Called on the day-read path so
     * it heals on the next fetch/poll. Returns the count re-enqueued; a no-op when
     * the image pipeline is unavailable.
     */
    public int sweepStalePending() {
        if (generator.getIfAvailable() == null || store.getIfAvailable() == null) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(PENDING_STALE_AFTER);
        List<CatalogFood> pending =
            repository.findByImageStatus(FoodImageStatus.PENDING, STALE_PENDING_LIMIT);
        int reenqueued = 0;
        for (CatalogFood food : pending) {
            if (food.updatedAt() != null && food.updatedAt().isBefore(cutoff)) {
                // enqueueGeneration re-flips PENDING (fresh updatedAt) then re-runs
                // generation off-thread — the same path the original create took.
                enqueueGeneration(food.foodId(), null);
                reenqueued++;
            }
        }
        return reenqueued;
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
