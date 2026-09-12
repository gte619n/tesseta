package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobException;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobQueue;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * "Remove Leftovers" (IMPL-LEFTOVER-01): photograph what's left on the plate and
 * subtract the uneaten portion from an already-logged photo meal.
 *
 * <p>Flow (async, spec D8):
 * <ol>
 *   <li>{@link #startAnalysis} flips the entry to {@code ANALYZING} (capturing the
 *       as-served baseline on the first pass), stores the leftover photo and
 *       enqueues a durable {@code LEFTOVER_ANALYSIS} job — returning immediately.</li>
 *   <li>{@link #analyzeFromRefOrThrow} (the job) reads back the ORIGINAL meal photo
 *       and the leftover photo, runs {@link LeftoverAnalyzer}, clamps via
 *       {@link LeftoverMath}, stores the proposal ({@code PENDING_REVIEW}) or marks
 *       it {@code REJECTED} (spec D12), discards the leftover photo (D11) and posts
 *       a completion notification (D13).</li>
 *   <li>{@link #apply}/{@link #discard}/{@link #restore} commit, drop or undo.</li>
 * </ol>
 *
 * <p>Lives in {@code core} and depends on the analyzer/store/reader ports via
 * {@link ObjectProvider} so core unit tests construct it without the integrations
 * beans (mirroring {@link MealCaptureService}).
 */
@Service
public class LeftoverService {

    private static final Logger log = LoggerFactory.getLogger(LeftoverService.class);

    /** Data-message type the Android client switches on to open the review screen. */
    public static final String NOTIF_REVIEW = "leftover-review";
    /** Data-message type the Android client switches on to prompt a retake. */
    public static final String NOTIF_RETAKE = "leftover-retake";

    private final ObjectProvider<LeftoverAnalyzer> analyzer;
    private final ObjectProvider<MealPhotoStore> photoStore;
    private final ObjectProvider<MealPhotoReader> photoReader;
    private final NutritionService nutrition;
    private final SyncChangeNotifier syncNotifier;
    private final ObjectProvider<NutritionJobQueue> jobQueue;
    private final LeftoverReviewPublisher reviewPublisher;

    public LeftoverService(
        ObjectProvider<LeftoverAnalyzer> analyzer,
        ObjectProvider<MealPhotoStore> photoStore,
        ObjectProvider<MealPhotoReader> photoReader,
        NutritionService nutrition,
        SyncChangeNotifier syncNotifier,
        ObjectProvider<NutritionJobQueue> jobQueue,
        LeftoverReviewPublisher reviewPublisher
    ) {
        this.analyzer = analyzer;
        this.photoStore = photoStore;
        this.photoReader = photoReader;
        this.nutrition = nutrition;
        this.syncNotifier = syncNotifier;
        this.jobQueue = jobQueue;
        this.reviewPublisher = reviewPublisher;
    }

    /**
     * Begin a leftover pass: validate eligibility (spec D5), snapshot the served
     * baseline if this is the first pass, store the leftover photo and kick the
     * durable analysis off-thread. Returns the {@code ANALYZING} entry.
     */
    public FoodEntry startAnalysis(
        String userId, LocalDate date, String entryId, byte[] leftoverBytes, String mime) {
        if (leftoverBytes == null || leftoverBytes.length == 0) {
            throw new IllegalArgumentException("leftover photo is required");
        }
        LeftoverAnalyzer a = analyzer.getIfAvailable();
        if (a == null) {
            throw new IllegalStateException("leftover analysis is not available");
        }
        // Validates D5 eligibility + not-found; flips to ANALYZING, captures baseline.
        FoodEntry analyzing = nutrition.beginLeftoverAnalysis(userId, date, entryId);

        String leftoverRef = storeLeftover(userId, leftoverBytes, mime);
        MealPhotoStore store = photoStore.getIfAvailable();
        NutritionJobQueue queue = jobQueue.getIfAvailable();
        if (queue != null && leftoverRef != null) {
            queue.enqueue(NutritionJob.leftoverAnalysis(
                userId, date.toString(), entryId, leftoverRef, mime));
        } else {
            // No durable queue (dev / core test): run inline, holding the bytes so
            // no ref read is needed for the leftover photo.
            final String refForCleanup = leftoverRef;
            CompletableFuture.runAsync(() -> runAnalysis(
                userId, date, entryId, leftoverBytes, mime, refForCleanup, store));
        }
        // Wake devices to render the "Analyzing leftovers…" state.
        syncNotifier.changed(userId, null, "nutritionDays/entries");
        return analyzing;
    }

    /**
     * Durable-queue entry point: re-read the stored leftover photo and the entry's
     * original photo, run the analysis and settle the pass. Idempotent — skips an
     * entry no longer {@code ANALYZING}. Throws {@link NutritionJobException} only
     * when the pipeline can't run at all (reader unavailable) so the queue retries.
     */
    public void analyzeFromRefOrThrow(
        String userId, LocalDate date, String entryId, String leftoverRef, String mime) {
        if (analyzer.getIfAvailable() == null) {
            return;
        }
        Optional<FoodEntry> found = nutrition.findEntry(userId, date, entryId);
        if (found.isEmpty() || found.get().leftover() == null
            || found.get().leftover().status() != LeftoverStatus.ANALYZING) {
            deleteLeftover(leftoverRef);
            return;
        }
        MealPhotoReader reader = photoReader.getIfAvailable();
        if (reader == null) {
            throw new NutritionJobException("meal photo reader unavailable for leftover " + entryId);
        }
        MealPhotoReader.Photo leftover = leftoverRef != null
            ? reader.read(leftoverRef).orElse(null) : null;
        if (leftover == null) {
            // The stored leftover photo is gone — nothing to analyze. Reject.
            reject(userId, date, entryId);
            deleteLeftover(leftoverRef);
            return;
        }
        runAnalysis(userId, date, entryId, leftover.bytes(),
            mime != null ? mime : leftover.mimeType(), leftoverRef, photoStore.getIfAvailable());
    }

    /** Mark a leftover pass failed from the queue (retries exhausted). */
    public void markFailed(String userId, LocalDate date, String entryId) {
        reject(userId, date, entryId);
        syncNotifier.changed(userId, null, "nutritionDays/entries");
    }

    /**
     * Run the analysis and settle the pass. Package-private + synchronous so tests
     * drive it without the async hop. Never throws — a failure rejects the pass.
     */
    void runAnalysis(
        String userId, LocalDate date, String entryId,
        byte[] leftoverBytes, String mime, String leftoverRef, MealPhotoStore store) {
        try {
            LeftoverAnalyzer a = analyzer.getIfAvailable();
            FoodEntry entry = nutrition.findEntry(userId, date, entryId).orElse(null);
            if (a == null || entry == null || entry.leftover() == null
                || entry.leftover().status() != LeftoverStatus.ANALYZING) {
                return;
            }
            List<CompositeIngredient> served = entry.leftover().servedIngredients();

            byte[] originalBytes = null;
            String originalMime = null;
            MealPhotoReader reader = photoReader.getIfAvailable();
            if (reader != null && entry.photoRef() != null && !entry.photoRef().isBlank()) {
                MealPhotoReader.Photo original = reader.read(entry.photoRef()).orElse(null);
                if (original != null) {
                    originalBytes = original.bytes();
                    originalMime = original.mimeType();
                }
            }

            LeftoverAnalyzer.ServedMeal servedMeal = toServedMeal(entry.foodName(), served);
            LeftoverAnalyzer.LeftoverEstimate estimate =
                a.estimate(servedMeal, originalBytes, originalMime, leftoverBytes, mime);
            LeftoverMath.Result result = LeftoverMath.compute(served, estimate);

            if (result.rejected()) {
                reject(userId, date, entryId);
                publishReview(userId, date, entryId, true, 0.0);
            } else {
                nutrition.setLeftoverProposal(userId, date, entryId, result.proposal());
                double deltaKcal = kcal(result.proposal().servedTotals())
                    - kcal(result.proposal().consumedTotals());
                publishReview(userId, date, entryId, false, deltaKcal);
            }
        } catch (RuntimeException e) {
            log.warn("Leftover analysis failed for entry {}: {}", entryId, e.getMessage());
            reject(userId, date, entryId);
            publishReview(userId, date, entryId, true, 0.0);
        } finally {
            // Discard the transient leftover photo (spec D11) and wake devices so
            // the PENDING_REVIEW / REJECTED state syncs in-app regardless of push.
            deleteLeftover(leftoverRef, store);
            syncNotifier.changed(userId, null, "nutritionDays/entries");
        }
    }

    /** Commit the pending proposal (spec D7/D13 Apply). */
    public FoodEntry apply(String userId, LocalDate date, String entryId) {
        FoodEntry updated = nutrition.applyLeftover(userId, date, entryId);
        syncNotifier.changed(userId, null, "nutritionDays/entries");
        return updated;
    }

    /** Discard a pending proposal (spec D7 Discard). */
    public Optional<FoodEntry> discard(String userId, LocalDate date, String entryId) {
        Optional<FoodEntry> updated = nutrition.discardLeftover(userId, date, entryId);
        updated.ifPresent(e -> syncNotifier.changed(userId, null, "nutritionDays/entries"));
        return updated;
    }

    /** Restore the full served portion (spec D15). */
    public FoodEntry restore(String userId, LocalDate date, String entryId) {
        FoodEntry updated = nutrition.restoreServedPortion(userId, date, entryId);
        syncNotifier.changed(userId, null, "nutritionDays/entries");
        return updated;
    }

    // ---- internals ----

    private void reject(String userId, LocalDate date, String entryId) {
        nutrition.rejectLeftover(userId, date, entryId);
    }

    private static LeftoverAnalyzer.ServedMeal toServedMeal(
        String mealName, List<CompositeIngredient> served) {
        List<LeftoverAnalyzer.ServedMeal.Item> items = new ArrayList<>();
        if (served != null) {
            for (CompositeIngredient ing : served) {
                items.add(new LeftoverAnalyzer.ServedMeal.Item(
                    ing.name(), LeftoverMath.effectiveGrams(ing)));
            }
        }
        return new LeftoverAnalyzer.ServedMeal(mealName, items);
    }

    private String storeLeftover(String userId, byte[] bytes, String mime) {
        MealPhotoStore store = photoStore.getIfAvailable();
        if (store == null) {
            return null;
        }
        try {
            return store.store(userId, bytes, mime);
        } catch (RuntimeException e) {
            log.warn("Failed to store leftover photo: {}", e.getMessage());
            return null;
        }
    }

    private void deleteLeftover(String ref) {
        deleteLeftover(ref, photoStore.getIfAvailable());
    }

    private void deleteLeftover(String ref, MealPhotoStore store) {
        if (ref == null || store == null) {
            return;
        }
        try {
            store.delete(ref);
        } catch (RuntimeException e) {
            log.debug("Leftover photo cleanup failed for {}: {}", ref, e.getMessage());
        }
    }

    /**
     * Publish the review/retake event (IL-6) — a listener turns it into the FCM
     * push (spec D13). Never throws; the in-app pending-review state is durable.
     */
    private void publishReview(
        String userId, LocalDate date, String entryId, boolean rejected, double deltaKcal) {
        try {
            reviewPublisher.reviewReady(new LeftoverReviewReadyEvent(
                userId, date.toString(), entryId, rejected, deltaKcal));
        } catch (RuntimeException e) {
            log.debug("Leftover review event failed for user {}: {}", userId, e.getMessage());
        }
    }

    private static double kcal(Macros m) {
        return m == null || m.caloriesKcal() == null ? 0.0 : m.caloriesKcal();
    }
}
