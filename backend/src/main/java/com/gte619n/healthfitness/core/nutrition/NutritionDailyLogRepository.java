package com.gte619n.healthfitness.core.nutrition;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NutritionDailyLogRepository {
    Optional<NutritionDailyLog> findByDate(String userId, LocalDate date);
    List<NutritionDailyLog> findByDateRange(String userId, LocalDate from, LocalDate to);
    void save(NutritionDailyLog log);

    /**
     * Atomically recompute the day's macro rollup from its food entries and
     * persist it, returning the saved rollup. Concurrent recomputes (triggered
     * by concurrent entry writes on the same day) must not lose an entry's
     * contribution.
     *
     * <p>The default is the historical non-atomic read-sum-write — fine for the
     * single-threaded in-memory fakes. The Firestore implementation overrides it
     * with a transaction that reads the entries and writes the rollup together
     * (serialising on the rollup doc), which is what prevents the lost update.
     */
    default NutritionDailyLog recomputeFromEntries(
        String userId, LocalDate date, FoodEntryRepository entries) {
        Macros total = Macros.zero();
        for (FoodEntry entry : entries.findByDate(userId, date)) {
            total = total.plus(entry.macros());
        }
        NutritionDailyLog log = rollupFrom(userId, date, total);
        save(log);
        return log;
    }

    /** Build the persisted rollup from a macro total (with derived calories),
     *  matching {@code NutritionService.logDay}. Shared by the default + Firestore impl. */
    static NutritionDailyLog rollupFrom(String userId, LocalDate date, Macros total) {
        Macros derived = total.withDerivedCalories();
        return new NutritionDailyLog(
            userId, date,
            derived.proteinGrams(), derived.carbsGrams(), derived.fatGrams(),
            derived.fiberGrams(), derived.sugarGrams(), derived.caloriesKcal(),
            null, null);
    }
}
