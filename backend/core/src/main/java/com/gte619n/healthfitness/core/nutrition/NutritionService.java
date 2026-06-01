package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import java.time.LocalDate;
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
        requireUser(userId);
        requireDate(date);
        if (meal == null) {
            throw new IllegalArgumentException("meal is required");
        }
        if (foodName == null || foodName.isBlank()) {
            throw new IllegalArgumentException("foodName is required");
        }
        String entryId = UUID.randomUUID().toString();
        FoodEntry entry = new FoodEntry(
            userId, date, entryId, meal, foodId, foodName, servingLabel,
            servingGrams, quantity, macros, null, source, null, null);
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
            existing.foodName(),
            servingLabel != null ? servingLabel : existing.servingLabel(),
            servingGrams != null ? servingGrams : existing.servingGrams(),
            quantity != null ? quantity : existing.quantity(),
            macros != null ? macros : existing.macros(),
            existing.photoRef(),
            existing.source(),
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
