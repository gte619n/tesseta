package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedEvent;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link NutritionService}: upsert-by-date semantics and that
 * a successful write publishes one {@link MetricChangedEvent} for each
 * of the four nutrition metric keys. Uses an in-memory repo and a
 * capturing {@link MetricChangedPublisher}.
 */
class NutritionServiceTest {

    private static final String USER = "u-nutri";

    @Test
    void logDay_persistsTheDay_andIsReadable() {
        InMemNutrition repo = new InMemNutrition();
        NutritionService svc = new NutritionService(repo, new InMemEntries(), capturingPublisher(new ArrayList<>()));

        LocalDate date = LocalDate.of(2026, 5, 20);
        svc.logDay(USER, date, 150.0, 200.0, 60.0, 25.0, 40.0, 2000.0);

        Optional<NutritionDailyLog> found = svc.findByDate(USER, date);
        assertTrue(found.isPresent());
        assertEquals(150.0, found.get().proteinGrams(), 1e-9);
        assertEquals(200.0, found.get().carbsGrams(), 1e-9);
        assertEquals(60.0, found.get().fatGrams(), 1e-9);
        assertEquals(2000.0, found.get().caloriesKcal(), 1e-9);
    }

    @Test
    void logDay_upsertsSameDate_ratherThanDuplicating() {
        InMemNutrition repo = new InMemNutrition();
        NutritionService svc = new NutritionService(repo, new InMemEntries(), capturingPublisher(new ArrayList<>()));

        LocalDate date = LocalDate.of(2026, 5, 20);
        svc.logDay(USER, date, 150.0, 200.0, 60.0, null, null, null);
        svc.logDay(USER, date, 180.0, 210.0, 70.0, null, null, null);

        List<NutritionDailyLog> range = svc.findRange(USER, date, date);
        assertEquals(1, range.size());
        assertEquals(180.0, range.get(0).proteinGrams(), 1e-9);
    }

    @Test
    void logDay_publishesAllNutritionMetrics() {
        InMemNutrition repo = new InMemNutrition();
        List<MetricChangedEvent> events = new ArrayList<>();
        NutritionService svc = new NutritionService(repo, new InMemEntries(), capturingPublisher(events));

        svc.logDay(USER, LocalDate.of(2026, 5, 20), 150.0, 200.0, 60.0, 25.0, 40.0, 2000.0);

        // The day's totals move every avg key plus targetMetDays.
        assertEquals(7, events.size());
        List<String> keys = events.stream().map(MetricChangedEvent::metricKey).toList();
        assertTrue(keys.contains(MetricKey.NUTRITION_PROTEIN_AVG_7D.key()));
        assertTrue(keys.contains(MetricKey.NUTRITION_CARBS_AVG_7D.key()));
        assertTrue(keys.contains(MetricKey.NUTRITION_FAT_AVG_7D.key()));
        assertTrue(keys.contains(MetricKey.NUTRITION_CALORIES_AVG_7D.key()));
        assertTrue(keys.contains(MetricKey.NUTRITION_FIBER_AVG_7D.key()));
        assertTrue(keys.contains(MetricKey.NUTRITION_SUGAR_AVG_7D.key()));
        assertTrue(keys.contains(MetricKey.NUTRITION_TARGET_MET_DAYS.key()));
        assertTrue(events.stream().allMatch(e -> e.userId().equals(USER)));
    }

    @Test
    void compositeMealPortion_scalesTheWholeMealTotal() {
        InMemNutrition rollups = new InMemNutrition();
        InMemEntries entries = new InMemEntries();
        NutritionService svc = new NutritionService(rollups, entries, capturingPublisher(new ArrayList<>()));
        LocalDate date = LocalDate.of(2026, 5, 20);

        FoodEntry meal = svc.addCompositeMeal(
            USER, date, MealType.LUNCH, "Salmon & Rice",
            List.of(ingredient("Salmon", 100.0), ingredient("Rice", 100.0)),
            EntrySource.PHOTO);
        assertEquals(200.0, meal.macros().caloriesKcal(), 1e-9);
        assertEquals(1.0, meal.quantity(), 1e-9);

        // "I ate half" — portion 0.5 halves the whole-meal total.
        FoodEntry half = svc.updateEntry(
            USER, date, meal.entryId(), null, null, null, null, 0.5, null);
        assertEquals(0.5, half.quantity(), 1e-9);
        assertEquals(100.0, half.macros().caloriesKcal(), 1e-9);
        assertEquals(100.0, svc.findByDate(USER, date).orElseThrow().caloriesKcal(), 1e-9);
    }

    @Test
    void editingAnIngredient_preservesTheMealPortion() {
        InMemNutrition rollups = new InMemNutrition();
        InMemEntries entries = new InMemEntries();
        NutritionService svc = new NutritionService(rollups, entries, capturingPublisher(new ArrayList<>()));
        LocalDate date = LocalDate.of(2026, 5, 20);

        FoodEntry meal = svc.addCompositeMeal(
            USER, date, MealType.LUNCH, "Salmon & Rice",
            List.of(ingredient("Salmon", 100.0), ingredient("Rice", 100.0)),
            EntrySource.PHOTO);
        svc.updateEntry(USER, date, meal.entryId(), null, null, null, null, 0.5, null);

        // Double the first ingredient: recipe sum 200+100=300, still × 0.5 portion.
        FoodEntry edited = svc.updateIngredient(USER, date, meal.entryId(), 0, null, null, 2.0);
        assertEquals(0.5, edited.quantity(), 1e-9);
        assertEquals(150.0, edited.macros().caloriesKcal(), 1e-9);
        assertEquals(150.0, svc.findByDate(USER, date).orElseThrow().caloriesKcal(), 1e-9);
    }

    /** A composite ingredient of {@code 100 g × 1} contributing {@code kcal} calories. */
    private static CompositeIngredient ingredient(String name, double kcal) {
        Macros per100g = new Macros(kcal, 0.0, 0.0, 0.0, 0.0, 0.0);
        return new CompositeIngredient(name, null, per100g, 100.0, "100 g", 1.0, per100g);
    }

    private static MetricChangedPublisher capturingPublisher(List<MetricChangedEvent> sink) {
        return new MetricChangedPublisher(event -> {
            if (event instanceof MetricChangedEvent e) sink.add(e);
        });
    }

    private static final class InMemNutrition implements NutritionDailyLogRepository {
        private final Map<String, NutritionDailyLog> rows = new ConcurrentHashMap<>();
        @Override public Optional<NutritionDailyLog> findByDate(String userId, LocalDate date) {
            return Optional.ofNullable(rows.get(date.toString()));
        }
        @Override public List<NutritionDailyLog> findByDateRange(String userId, LocalDate from, LocalDate to) {
            return rows.values().stream()
                .filter(l -> !l.date().isBefore(from) && !l.date().isAfter(to))
                .sorted(java.util.Comparator.comparing(NutritionDailyLog::date))
                .toList();
        }
        @Override public void save(NutritionDailyLog log) { rows.put(log.date().toString(), log); }
    }

    private static final class InMemEntries implements FoodEntryRepository {
        private final Map<String, FoodEntry> rows = new ConcurrentHashMap<>();
        private static String key(LocalDate date, String entryId) { return date + "/" + entryId; }
        @Override public List<FoodEntry> findByDate(String userId, LocalDate date) {
            return rows.values().stream().filter(e -> e.date().equals(date)).toList();
        }
        @Override public Optional<FoodEntry> findById(String userId, LocalDate date, String entryId) {
            return Optional.ofNullable(rows.get(key(date, entryId)));
        }
        @Override public Optional<FoodEntry> findByContentHash(String userId, LocalDate date, String contentHash) {
            return rows.values().stream()
                .filter(e -> e.date().equals(date) && contentHash != null && contentHash.equals(e.contentHash()))
                .findFirst();
        }
        @Override public void save(FoodEntry entry) { rows.put(key(entry.date(), entry.entryId()), entry); }
        @Override public void delete(String userId, LocalDate date, String entryId) { rows.remove(key(date, entryId)); }
    }
}
