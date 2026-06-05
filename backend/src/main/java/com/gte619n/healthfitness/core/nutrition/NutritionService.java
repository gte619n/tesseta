package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes and reads the user's daily nutrition log. A day is keyed by
 * {@code (userId, date)} — {@link #logDay} upserts that day's
 * {@link NutritionDailyLog} rather than appending duplicates.
 *
 * <p>The daily log is a cached rollup: per-food {@link FoodEntry} rows are the
 * source of truth and {@link #recomputeDay} resums them into the day log on
 * every entry mutation. Both {@code logDay} and the recompute path save through
 * the same repo, which fires one {@link MetricChangedEvent} per nutrition
 * metric key (protein/carbs/fat/calories) so any bound Steps re-evaluate —
 * published AFTER the save, never before.
 *
 * <p>Timestamps follow the codebase convention: the record carries
 * {@code null} for created/updated and the Firestore repo stamps the
 * server timestamp on write.
 */
@Service
public class NutritionService {

    private static final List<MetricKey> NUTRITION_KEYS = List.of(
        MetricKey.NUTRITION_PROTEIN_AVG_7D,
        MetricKey.NUTRITION_CARBS_AVG_7D,
        MetricKey.NUTRITION_FAT_AVG_7D,
        MetricKey.NUTRITION_CALORIES_AVG_7D,
        MetricKey.NUTRITION_FIBER_AVG_7D,
        MetricKey.NUTRITION_SUGAR_AVG_7D,
        // The day's totals also move "days target met", so bound COUNT Steps
        // re-evaluate after every rollup save.
        MetricKey.NUTRITION_TARGET_MET_DAYS
    );

    // A photo placeholder still ANALYZING past this age is presumed orphaned —
    // its in-memory analysis task died with the instance (OOM/restart/deploy) and
    // will never finish — so a day read fails it rather than spin forever.
    private static final Duration ANALYSIS_STALE_AFTER = Duration.ofMinutes(5);

    private final NutritionDailyLogRepository repository;
    private final FoodEntryRepository entries;
    private final MetricChangedPublisher metricChangedPublisher;

    public NutritionService(
        NutritionDailyLogRepository repository,
        FoodEntryRepository entries,
        MetricChangedPublisher metricChangedPublisher
    ) {
        this.repository = repository;
        this.entries = entries;
        this.metricChangedPublisher = metricChangedPublisher;
    }

    /**
     * Upsert the nutrition log for {@code date}. Any existing row for
     * that day is replaced. {@code caloriesKcal} is optional — when
     * null, the calories metric is derived from macros at read time.
     */
    public NutritionDailyLog logDay(
        String userId,
        LocalDate date,
        Double proteinGrams,
        Double carbsGrams,
        Double fatGrams,
        Double fiberGrams,
        Double sugarGrams,
        Double caloriesKcal
    ) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        NutritionDailyLog log = new NutritionDailyLog(
            userId, date, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams, caloriesKcal, null, null);
        repository.save(log);
        // Publish after the save so a failed save never fires events.
        metricChangedPublisher.publishAll(userId, NUTRITION_KEYS);
        return log;
    }

    public Optional<NutritionDailyLog> findByDate(String userId, LocalDate date) {
        return repository.findByDate(userId, date);
    }

    public List<NutritionDailyLog> findRange(String userId, LocalDate from, LocalDate to) {
        return repository.findByDateRange(userId, from, to);
    }

    // ----- Food entries -------------------------------------------------

    public List<FoodEntry> listEntries(String userId, LocalDate date) {
        requireUser(userId);
        requireDate(date);
        return entries.findByDate(userId, date);
    }

    /** Look up a single entry by id (used for idempotent-create replay, D7). */
    public Optional<FoodEntry> findEntry(String userId, LocalDate date, String entryId) {
        requireUser(userId);
        requireDate(date);
        return entries.findById(userId, date, entryId);
    }

    /**
     * Add a food entry to {@code date}. The caller supplies the already-computed
     * {@code macros} snapshot; the service stores it verbatim, generates an
     * {@code entryId}, then recomputes the day rollup.
     */
    public FoodEntry addEntry(
        String userId,
        LocalDate date,
        MealType meal,
        String foodId,
        String foodName,
        String servingLabel,
        Double servingGrams,
        Double quantity,
        Macros macros,
        EntrySource source
    ) {
        return addEntry(userId, date, meal, foodId, foodName, servingLabel,
            servingGrams, quantity, macros, source, null);
    }

    /**
     * Add a food entry with an optional client-minted {@code entryId}
     * (IMPL-AND-20 D7). When {@code entryId} is null/blank a server UUID is
     * generated, preserving the previous behaviour. Look an entry back up via
     * {@link #findEntry} for idempotent replay.
     */
    public FoodEntry addEntry(
        String userId,
        LocalDate date,
        MealType meal,
        String foodId,
        String foodName,
        String servingLabel,
        Double servingGrams,
        Double quantity,
        Macros macros,
        EntrySource source,
        String entryId
    ) {
        requireUser(userId);
        requireDate(date);
        if (meal == null) {
            throw new IllegalArgumentException("meal is required");
        }
        if (foodName == null || foodName.isBlank()) {
            throw new IllegalArgumentException("foodName is required");
        }
        if (entryId == null || entryId.isBlank()) {
            entryId = UUID.randomUUID().toString();
        }
        FoodEntry entry = new FoodEntry(
            userId, date, entryId, meal, foodId, foodName, servingLabel,
            servingGrams, quantity, macros, null, null, source,
            null, null, FoodImageStatus.NONE, EntryAnalysisStatus.NONE, null, null);
        entries.save(entry);
        recomputeDay(userId, date);
        return entry;
    }

    /**
     * Log a composite meal (a photo-logged plate) as a single entry carrying its
     * {@code ingredients}. The entry's macros are the sum of the ingredients'
     * portion macros; the finished-meal image is generated separately (the
     * entry starts {@code mealImageStatus = NONE}, flipped to PENDING when the
     * image service enqueues generation).
     */
    public FoodEntry addCompositeMeal(
        String userId,
        LocalDate date,
        MealType meal,
        String mealName,
        List<CompositeIngredient> ingredients,
        EntrySource source
    ) {
        return addCompositeMeal(userId, date, meal, mealName, ingredients, source, null);
    }

    /**
     * As {@link #addCompositeMeal(String, LocalDate, MealType, String, List,
     * EntrySource)} but honoring a client-minted {@code entryId} (IMPL-AND-20
     * D7); a null/blank {@code entryId} falls back to a server-generated UUID.
     */
    public FoodEntry addCompositeMeal(
        String userId,
        LocalDate date,
        MealType meal,
        String mealName,
        List<CompositeIngredient> ingredients,
        EntrySource source,
        String entryId
    ) {
        requireUser(userId);
        requireDate(date);
        if (meal == null) {
            throw new IllegalArgumentException("meal is required");
        }
        if (mealName == null || mealName.isBlank()) {
            throw new IllegalArgumentException("mealName is required");
        }
        if (ingredients == null || ingredients.isEmpty()) {
            throw new IllegalArgumentException("a composite meal needs at least one ingredient");
        }
        Macros total = Macros.zero();
        double grams = 0.0;
        for (CompositeIngredient ing : ingredients) {
            total = total.plus(ing.macros());
            if (ing.servingGrams() != null) {
                grams += ing.servingGrams() * (ing.quantity() != null ? ing.quantity() : 1.0);
            }
        }
        if (entryId == null || entryId.isBlank()) {
            entryId = UUID.randomUUID().toString();
        }
        FoodEntry entry = new FoodEntry(
            userId, date, entryId, meal, null, mealName,
            ingredients.size() + " ingredients", grams, 1.0, total, null, null, source,
            List.copyOf(ingredients), null, FoodImageStatus.NONE, EntryAnalysisStatus.NONE, null, null);
        entries.save(entry);
        recomputeDay(userId, date);
        return entry;
    }

    // ----- Background photo-analysis lifecycle --------------------------

    /**
     * Create a placeholder entry for a freshly captured photo: it carries the
     * stored {@code photoRef}, zero macros and {@code ANALYZING} status, and is
     * filled in later by {@link #finalizeCompositeMeal}/{@link #finalizeSingleFood}
     * once the background AI analysis returns. The day rollup is unaffected (the
     * placeholder contributes zero) until the entry is finalized.
     */
    public FoodEntry beginAnalyzingEntry(
        String userId, LocalDate date, MealType meal, String photoRef) {
        return beginAnalyzingEntry(userId, date, meal, photoRef, null);
    }

    /**
     * As {@link #beginAnalyzingEntry(String, LocalDate, MealType, String)} but
     * stamping the photo's {@code contentHash} so a later re-upload of the same
     * image can be deduped — see {@link #findActivePhotoByHash}.
     */
    public FoodEntry beginAnalyzingEntry(
        String userId, LocalDate date, MealType meal, String photoRef, String contentHash) {
        requireUser(userId);
        requireDate(date);
        if (meal == null) {
            throw new IllegalArgumentException("meal is required");
        }
        String entryId = UUID.randomUUID().toString();
        FoodEntry entry = new FoodEntry(
            userId, date, entryId, meal, null, "Analyzing photo…",
            null, null, 1.0, Macros.zero(), photoRef, contentHash, EntrySource.PHOTO,
            null, null, FoodImageStatus.NONE, EntryAnalysisStatus.ANALYZING, null, null);
        entries.save(entry);
        return entry;
    }

    /**
     * Find a still-relevant photo entry on this day whose captured image matches
     * {@code contentHash}. {@code FAILED} captures are excluded so a user can
     * re-upload the same image to retry an analysis that didn't recognize it.
     * Used by the capture path to silently dedupe re-uploads of the same photo.
     */
    public Optional<FoodEntry> findActivePhotoByHash(
        String userId, LocalDate date, String contentHash) {
        requireUser(userId);
        requireDate(date);
        if (contentHash == null || contentHash.isBlank()) {
            return Optional.empty();
        }
        return entries.findByContentHash(userId, date, contentHash)
            .filter(e -> e.analysisStatus() != EntryAnalysisStatus.FAILED);
    }

    /**
     * Fill in an {@code ANALYZING} placeholder as a composite (multi-ingredient)
     * meal: set its name, ingredients and summed macros and flip it to
     * {@code READY}. Recomputes the day rollup. No-op if the entry vanished.
     */
    public FoodEntry finalizeCompositeMeal(
        String userId, LocalDate date, String entryId,
        String mealName, List<CompositeIngredient> ingredients) {
        requireUser(userId);
        requireDate(date);
        if (mealName == null || mealName.isBlank()) {
            throw new IllegalArgumentException("mealName is required");
        }
        if (ingredients == null || ingredients.isEmpty()) {
            throw new IllegalArgumentException("a composite meal needs at least one ingredient");
        }
        FoodEntry existing = entries.findById(userId, date, entryId)
            .orElseThrow(() -> new IllegalArgumentException("entry not found: " + entryId));
        Macros total = Macros.zero();
        double grams = 0.0;
        for (CompositeIngredient ing : ingredients) {
            total = total.plus(ing.macros());
            if (ing.servingGrams() != null) {
                grams += ing.servingGrams() * (ing.quantity() != null ? ing.quantity() : 1.0);
            }
        }
        FoodEntry updated = new FoodEntry(
            existing.userId(), existing.date(), existing.entryId(), existing.meal(),
            null, mealName, ingredients.size() + " ingredients", grams, 1.0, total,
            existing.photoRef(), existing.contentHash(), existing.source(), List.copyOf(ingredients),
            existing.mealImageUrl(), existing.mealImageStatus(),
            EntryAnalysisStatus.READY, existing.createdAt(), null);
        entries.save(updated);
        recomputeDay(userId, date);
        return updated;
    }

    /**
     * Fill in an {@code ANALYZING} placeholder as a single food (a packaged
     * product): set its catalog {@code foodId}, name, portion and macros and flip
     * it to {@code READY}. The product's studio image rides on the catalog food.
     */
    public FoodEntry finalizeSingleFood(
        String userId, LocalDate date, String entryId,
        String foodId, String foodName, String servingLabel,
        Double servingGrams, Double quantity, Macros macros) {
        requireUser(userId);
        requireDate(date);
        if (foodName == null || foodName.isBlank()) {
            throw new IllegalArgumentException("foodName is required");
        }
        FoodEntry existing = entries.findById(userId, date, entryId)
            .orElseThrow(() -> new IllegalArgumentException("entry not found: " + entryId));
        FoodEntry updated = new FoodEntry(
            existing.userId(), existing.date(), existing.entryId(), existing.meal(),
            foodId, foodName, servingLabel, servingGrams,
            quantity != null ? quantity : 1.0, macros, existing.photoRef(),
            existing.contentHash(), existing.source(), null, null, FoodImageStatus.NONE,
            EntryAnalysisStatus.READY, existing.createdAt(), null);
        entries.save(updated);
        recomputeDay(userId, date);
        return updated;
    }

    /**
     * Mark a placeholder entry's analysis as {@code FAILED} (the photo couldn't
     * be understood). Keeps the entry so the client can surface the failure and
     * let the user retry or delete it.
     */
    public void markAnalysisFailed(String userId, LocalDate date, String entryId) {
        requireUser(userId);
        requireDate(date);
        entries.findById(userId, date, entryId).ifPresent(e -> {
            FoodEntry failed = new FoodEntry(
                e.userId(), e.date(), e.entryId(), e.meal(), e.foodId(),
                "Couldn’t read photo", e.servingLabel(), e.servingGrams(),
                e.quantity(), e.macros(), e.photoRef(), e.contentHash(), e.source(), e.ingredients(),
                e.mealImageUrl(), e.mealImageStatus(), EntryAnalysisStatus.FAILED,
                e.createdAt(), null);
            entries.save(failed);
        });
    }

    /**
     * Self-heal orphaned photo placeholders: fail any entry that has been
     * {@code ANALYZING} longer than {@link #ANALYSIS_STALE_AFTER}. The analysis
     * runs in an in-memory task with no retry, so an instance OOM/restart/deploy
     * mid-analysis leaves the entry ANALYZING forever. Called on the day-read
     * path so it heals on the next fetch the user (or their poll) makes.
     */
    public void sweepStaleAnalyzing(String userId, LocalDate date) {
        requireUser(userId);
        requireDate(date);
        Instant cutoff = Instant.now().minus(ANALYSIS_STALE_AFTER);
        for (FoodEntry e : entries.findByDate(userId, date)) {
            if (e.analysisStatus() == EntryAnalysisStatus.ANALYZING
                && e.createdAt() != null
                && e.createdAt().isBefore(cutoff)) {
                markAnalysisFailed(userId, date, e.entryId());
            }
        }
    }

    /**
     * Re-portion one ingredient of a composite meal (by list index), re-scaling
     * its macros from its per-100g baseline, then resum the entry total and the
     * day rollup.
     */
    public FoodEntry updateIngredient(
        String userId,
        LocalDate date,
        String entryId,
        int index,
        Double servingGrams,
        String servingLabel,
        Double quantity
    ) {
        requireUser(userId);
        requireDate(date);
        FoodEntry existing = entries.findById(userId, date, entryId)
            .orElseThrow(() -> new IllegalArgumentException("entry not found: " + entryId));
        List<CompositeIngredient> current = existing.ingredients();
        if (current == null || index < 0 || index >= current.size()) {
            throw new IllegalArgumentException("invalid ingredient index: " + index);
        }
        List<CompositeIngredient> updated = new ArrayList<>(current);
        updated.set(index, current.get(index).withPortion(servingGrams, servingLabel, quantity));

        Macros total = Macros.zero();
        for (CompositeIngredient ing : updated) {
            total = total.plus(ing.macros());
        }
        FoodEntry entry = new FoodEntry(
            existing.userId(), existing.date(), existing.entryId(), existing.meal(),
            existing.foodId(), existing.foodName(), existing.servingLabel(),
            existing.servingGrams(), existing.quantity(), total, existing.photoRef(),
            existing.contentHash(), existing.source(), updated, existing.mealImageUrl(), existing.mealImageStatus(),
            existing.analysisStatus(), existing.createdAt(), null);
        entries.save(entry);
        recomputeDay(userId, date);
        return entry;
    }

    /**
     * Partially update an existing entry. Null arguments leave the existing
     * value untouched. Recomputes the day rollup afterwards.
     */
    public FoodEntry updateEntry(
        String userId,
        LocalDate date,
        String entryId,
        MealType meal,
        String foodName,
        String servingLabel,
        Double servingGrams,
        Double quantity,
        Macros macros
    ) {
        requireUser(userId);
        requireDate(date);
        FoodEntry existing = entries.findById(userId, date, entryId)
            .orElseThrow(() -> new IllegalArgumentException("entry not found: " + entryId));
        FoodEntry updated = new FoodEntry(
            existing.userId(),
            existing.date(),
            existing.entryId(),
            meal != null ? meal : existing.meal(),
            existing.foodId(),
            foodName != null && !foodName.isBlank() ? foodName : existing.foodName(),
            servingLabel != null ? servingLabel : existing.servingLabel(),
            servingGrams != null ? servingGrams : existing.servingGrams(),
            quantity != null ? quantity : existing.quantity(),
            macros != null ? macros : existing.macros(),
            existing.photoRef(),
            existing.contentHash(),
            existing.source(),
            existing.ingredients(),
            existing.mealImageUrl(),
            existing.mealImageStatus(),
            existing.analysisStatus(),
            existing.createdAt(),
            null
        );
        entries.save(updated);
        recomputeDay(userId, date);
        return updated;
    }

    public void deleteEntry(String userId, LocalDate date, String entryId) {
        requireUser(userId);
        requireDate(date);
        entries.delete(userId, date, entryId);
        recomputeDay(userId, date);
    }

    /**
     * Resum all of the day's entries into the cached {@link NutritionDailyLog}
     * and save it through the existing repo (which publishes the metric events).
     */
    private void recomputeDay(String userId, LocalDate date) {
        Macros total = Macros.zero();
        for (FoodEntry e : entries.findByDate(userId, date)) {
            total = total.plus(e.macros());
        }
        logDay(
            userId,
            date,
            total.proteinGrams(),
            total.carbsGrams(),
            total.fatGrams(),
            total.fiberGrams(),
            total.sugarGrams(),
            total.caloriesKcal()
        );
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private static void requireDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
    }
}
