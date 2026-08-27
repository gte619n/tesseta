package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobException;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobQueue;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Generates the AI <strong>finished-meal</strong> image for a composite meal
 * entry — the plated dish, as opposed to the per-ingredient raw images carried
 * by each ingredient's catalog food. Mirrors {@link FoodImageService} but writes
 * the result onto the {@link FoodEntry} ({@code mealImageUrl}/
 * {@code mealImageStatus}) instead of a catalog food.
 *
 * <p>The finished meal is itself a plated dish, so it reuses the existing
 * {@link FoodImageGenerator} (fed a transient subject named after the meal, with
 * the user's capture photo as a visual reference) and {@link FoodImageStore}.
 * When those ports are unavailable (images disabled / core-only test context)
 * enqueueing is a graceful no-op and the entry stays {@code NONE}.
 */
@Service
public class FoodEntryImageService {

    private static final Logger log = LoggerFactory.getLogger(FoodEntryImageService.class);

    private final FoodEntryRepository entries;
    private final ObjectProvider<FoodImageGenerator> generator;
    private final ObjectProvider<FoodImageStore> store;
    private final ObjectProvider<MealPhotoReader> photoReader;
    private final SyncChangeNotifier syncNotifier;
    private final ObjectProvider<NutritionJobQueue> jobQueue;

    public FoodEntryImageService(
        FoodEntryRepository entries,
        ObjectProvider<FoodImageGenerator> generator,
        ObjectProvider<FoodImageStore> store,
        ObjectProvider<MealPhotoReader> photoReader,
        SyncChangeNotifier syncNotifier,
        ObjectProvider<NutritionJobQueue> jobQueue
    ) {
        this.entries = entries;
        this.generator = generator;
        this.store = store;
        this.photoReader = photoReader;
        this.syncNotifier = syncNotifier;
        this.jobQueue = jobQueue;
    }

    /**
     * Enqueue async finished-meal image generation for a composite entry.
     * Flips the entry to {@code PENDING} synchronously, then runs off-thread.
     */
    public void enqueueGeneration(
        String userId, LocalDate date, String entryId, String mealName, String referencePhotoRef) {
        if (entryId == null || entryId.isBlank()) {
            return;
        }
        if (generator.getIfAvailable() == null || store.getIfAvailable() == null) {
            return;
        }
        markStatus(userId, date, entryId, FoodImageStatus.PENDING, null);
        NutritionJobQueue queue = jobQueue.getIfAvailable();
        if (queue != null) {
            queue.enqueue(NutritionJob.entryImage(
                userId, date.toString(), entryId, mealName, referencePhotoRef));
        } else {
            CompletableFuture.runAsync(
                () -> generateNow(userId, date, entryId, mealName, referencePhotoRef));
        }
    }

    /**
     * Single-attempt generation (backfill / local executor): walks the entry to
     * {@code READY} or {@code FAILED}. Never throws.
     */
    public void generateNow(
        String userId, LocalDate date, String entryId, String mealName, String referencePhotoRef) {
        try {
            generateOrThrow(userId, date, entryId, mealName, referencePhotoRef);
        } catch (RuntimeException e) {
            log.warn("Composite meal image generation failed for {}: {}", entryId, e.getMessage());
            markFailed(userId, date, entryId);
        }
    }

    /**
     * Generate + upload the finished-meal image, marking the entry {@code READY}
     * and waking the user's devices on success. Throws {@link NutritionJobException}
     * when generation produced nothing so the durable queue can retry. Idempotent:
     * a redelivered job whose image already landed (or whose subject is cached)
     * completes without another Gemini call.
     */
    public void generateOrThrow(
        String userId, LocalDate date, String entryId, String mealName, String referencePhotoRef) {
        FoodImageGenerator gen = generator.getIfAvailable();
        FoodImageStore storage = store.getIfAvailable();
        if (gen == null || storage == null) {
            return;
        }
        Optional<FoodEntry> found = entries.findById(userId, date, entryId);
        if (found.isEmpty()) {
            return;
        }
        // Idempotent: a redelivered job whose image already landed is a no-op.
        if (found.get().mealImageStatus() == FoodImageStatus.READY
            && found.get().mealImageUrl() != null) {
            return;
        }

        MealPhotoReader.Photo reference = loadReference(referencePhotoRef);
        byte[] refBytes = reference == null ? null : reference.bytes();
        String refMime = reference == null ? null : reference.mimeType();

        CatalogFood subject = mealSubject(entryId, mealName);
        // A described meal (no capture photo) is a plated dish keyed by name,
        // so identical meals reuse one image; a photo-referenced meal is unique.
        boolean cacheable = refBytes == null || refBytes.length == 0;
        String cacheKey = cacheable ? FoodImageCacheKey.of(subject) : null;
        if (cacheable) {
            Optional<String> cached = storage.findCachedUrl(cacheKey);
            if (cached.isPresent()) {
                markStatus(userId, date, entryId, FoodImageStatus.READY, cached.get());
                notifyDone(userId);
                return;
            }
        }

        Optional<byte[]> image = gen.generate(subject, refBytes, refMime);
        if (image.isEmpty() || image.get().length == 0) {
            throw new NutritionJobException("meal image generation returned no image for " + entryId);
        }
        String url = storage.upload(entryId, image.get());
        if (cacheable) {
            storage.putCachedUrl(cacheKey, url);
        }
        markStatus(userId, date, entryId, FoodImageStatus.READY, url);
        notifyDone(userId);
    }

    /** Record a terminal failure (queue retries exhausted) and wake the devices. */
    public void markFailed(String userId, LocalDate date, String entryId) {
        markStatus(userId, date, entryId, FoodImageStatus.FAILED, null);
        notifyDone(userId);
    }

    /**
     * Wake the user's devices once the finished-meal image reaches a terminal
     * state. Image generation runs well after the entry itself finalizes (which
     * sent the only earlier push), so without this the image lands silently and
     * the foreground screen only shows it if its short settle-poll happens to
     * still be running — the root of "sometimes the image comes back, sometimes
     * it doesn't". Origin is {@code null} so every device refreshes.
     */
    private void notifyDone(String userId) {
        syncNotifier.changed(userId, null, "nutritionDays/entries");
    }

    /** A transient catalog-food subject so the plated-dish generator has a name. */
    private static CatalogFood mealSubject(String entryId, String mealName) {
        String name = (mealName == null || mealName.isBlank()) ? "a plated meal" : mealName;
        return new CatalogFood(
            entryId, name, name.toLowerCase(), null, null, null, null, List.of(), 0,
            FoodSource.GEMINI_PHOTO, null, FoodStatus.UNVERIFIED, 0, null, null,
            FoodImageStatus.PENDING, null, null, null);
    }

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

    private void markStatus(
        String userId, LocalDate date, String entryId, FoodImageStatus status, String url) {
        entries.findById(userId, date, entryId).ifPresent(e -> {
            FoodEntry updated = new FoodEntry(
                e.userId(), e.date(), e.entryId(), e.meal(), e.foodId(), e.foodName(),
                e.servingLabel(), e.servingGrams(), e.quantity(), e.macros(), e.photoRef(),
                e.contentHash(), e.source(), e.ingredients(),
                url != null ? url : e.mealImageUrl(), status, e.analysisStatus(),
                e.createdAt(), Instant.now());
            entries.save(updated);
        });
    }
}
