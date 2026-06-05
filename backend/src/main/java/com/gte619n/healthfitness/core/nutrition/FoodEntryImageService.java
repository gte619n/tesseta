package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

    private final FoodEntryRepository entries;
    private final ObjectProvider<FoodImageGenerator> generator;
    private final ObjectProvider<FoodImageStore> store;
    private final ObjectProvider<MealPhotoReader> photoReader;

    public FoodEntryImageService(
        FoodEntryRepository entries,
        ObjectProvider<FoodImageGenerator> generator,
        ObjectProvider<FoodImageStore> store,
        ObjectProvider<MealPhotoReader> photoReader
    ) {
        this.entries = entries;
        this.generator = generator;
        this.store = store;
        this.photoReader = photoReader;
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
        CompletableFuture.runAsync(
            () -> generateNow(userId, date, entryId, mealName, referencePhotoRef));
    }

    /** Synchronous generation; walks the entry to READY or FAILED. Never throws. */
    public void generateNow(
        String userId, LocalDate date, String entryId, String mealName, String referencePhotoRef) {
        FoodImageGenerator gen = generator.getIfAvailable();
        FoodImageStore storage = store.getIfAvailable();
        if (gen == null || storage == null) {
            return;
        }
        if (entries.findById(userId, date, entryId).isEmpty()) {
            return;
        }
        try {
            MealPhotoReader.Photo reference = loadReference(referencePhotoRef);
            byte[] refBytes = reference == null ? null : reference.bytes();
            String refMime = reference == null ? null : reference.mimeType();

            Optional<byte[]> image = gen.generate(mealSubject(entryId, mealName), refBytes, refMime);
            if (image.isEmpty() || image.get().length == 0) {
                markStatus(userId, date, entryId, FoodImageStatus.FAILED, null);
                return;
            }
            String url = storage.upload(entryId, image.get());
            markStatus(userId, date, entryId, FoodImageStatus.READY, url);
        } catch (RuntimeException e) {
            System.err.println(
                "Composite meal image generation failed for " + entryId + ": " + e.getMessage());
            markStatus(userId, date, entryId, FoodImageStatus.FAILED, null);
        }
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
