package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.core.push.SyncChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit-tests {@link FoodEntryImageService}: the finished-meal image walks to a
 * terminal state AND fires a sync notification so the client learns the image
 * landed. Without that push a composite meal's image generated after the entry
 * finalized would appear only if the foreground settle-poll happened to still be
 * running — the "sometimes the image comes back, sometimes it doesn't" jank.
 */
class FoodEntryImageServiceTest {

    private static final String USER = "u1";
    private static final LocalDate DATE = LocalDate.of(2026, 8, 3);
    private static final byte[] PNG = "png-bytes".getBytes();

    @Test
    void generateNow_reachesReadyAndNotifies() {
        InMemEntries entries = new InMemEntries();
        entries.save(entry("e1", FoodImageStatus.PENDING));
        RecordingNotifier notifier = new RecordingNotifier();
        FoodEntryImageService svc = new FoodEntryImageService(
            entries, provider(gen(Optional.of(PNG))), provider(store("https://img/e1.png")),
            empty(), notifier.asNotifier(), empty());

        svc.generateNow(USER, DATE, "e1", "Chicken bowl", null);

        assertEquals(FoodImageStatus.READY, entries.findById(USER, DATE, "e1").orElseThrow().mealImageStatus());
        assertTrue(notifier.notifiedNutritionFor(USER), "READY image must wake the user's devices");
    }

    @Test
    void generateNow_emptyImageMarksFailedAndNotifies() {
        InMemEntries entries = new InMemEntries();
        entries.save(entry("e1", FoodImageStatus.PENDING));
        RecordingNotifier notifier = new RecordingNotifier();
        FoodEntryImageService svc = new FoodEntryImageService(
            entries, provider(gen(Optional.empty())), provider(store("https://img/e1.png")),
            empty(), notifier.asNotifier(), empty());

        svc.generateNow(USER, DATE, "e1", "Chicken bowl", null);

        assertEquals(FoodImageStatus.FAILED, entries.findById(USER, DATE, "e1").orElseThrow().mealImageStatus());
        assertTrue(notifier.notifiedNutritionFor(USER), "a FAILED image must also wake devices so the row stops spinning");
    }

    @Test
    void sweepStale_healsNoneCompositeImmediately() {
        InMemEntries entries = new InMemEntries();
        // A composite meal whose image was never generated (NONE) — the "Blueberries"
        // dead-end: a permanent placeholder with no recovery.
        entries.save(composite("e1", FoodImageStatus.NONE, Instant.now()));
        FoodEntryImageService svc = svc(entries);

        int healed = svc.sweepStale(USER, DATE, entries.findByDate(USER, DATE));

        assertEquals(1, healed);
        assertNotEquals(FoodImageStatus.NONE, entries.findById(USER, DATE, "e1").orElseThrow().mealImageStatus(),
            "a NONE composite must be re-enqueued (flipped off NONE) so its picture generates");
    }

    @Test
    void sweepStale_reenqueuesStalePendingButNotFresh() {
        InMemEntries entries = new InMemEntries();
        entries.save(composite("fresh", FoodImageStatus.PENDING, Instant.now()));
        entries.save(composite("stale", FoodImageStatus.PENDING, Instant.now().minus(Duration.ofMinutes(5))));
        FoodEntryImageService svc = svc(entries);

        int healed = svc.sweepStale(USER, DATE, entries.findByDate(USER, DATE));

        // Only the orphaned (stale) PENDING is re-enqueued; a healthy in-flight job is left alone.
        assertEquals(1, healed);
    }

    @Test
    void sweepStale_ignoresSingleFoodAndReadyEntries() {
        InMemEntries entries = new InMemEntries();
        entries.save(entry("single", FoodImageStatus.NONE));          // single food: image joins from catalog
        entries.save(composite("ready", FoodImageStatus.READY, Instant.now().minus(Duration.ofMinutes(5))));
        FoodEntryImageService svc = svc(entries);

        int healed = svc.sweepStale(USER, DATE, entries.findByDate(USER, DATE));

        assertEquals(0, healed);
    }

    // ---- fakes ----

    private FoodEntryImageService svc(InMemEntries entries) {
        return new FoodEntryImageService(
            entries, provider(gen(Optional.of(PNG))), provider(store("https://img/e.png")),
            empty(), new RecordingNotifier().asNotifier(), empty());
    }

    private static FoodEntry entry(String entryId, FoodImageStatus imageStatus) {
        return new FoodEntry(
            USER, DATE, entryId, MealType.LUNCH, null, "Chicken bowl", "1 bowl", 400.0, 1.0,
            new Macros(500.0, 40.0, 30.0, 20.0, 0.0, 0.0), null, null, EntrySource.PHOTO,
            List.of(), null, imageStatus, EntryAnalysisStatus.READY, Instant.now(), Instant.now());
    }

    /** A composite (ingredient-bearing) meal entry with an explicit updatedAt. */
    private static FoodEntry composite(String entryId, FoodImageStatus imageStatus, Instant updatedAt) {
        return new FoodEntry(
            USER, DATE, entryId, MealType.LUNCH, null, "Chicken bowl", "1 bowl", 400.0, 1.0,
            new Macros(500.0, 40.0, 30.0, 20.0, 0.0, 0.0), null, null, EntrySource.PHOTO,
            List.of(new CompositeIngredient(
                "Chicken", null, new Macros(165.0, 31.0, 0.0, 3.6, 0.0, 0.0), 200.0, "100 g", 1.0,
                new Macros(330.0, 62.0, 0.0, 7.2, 0.0, 0.0))),
            null, imageStatus, EntryAnalysisStatus.READY, Instant.now(), updatedAt);
    }

    private static FoodImageGenerator gen(Optional<byte[]> result) {
        return (food, ref, mime) -> result;
    }

    private static FoodImageStore store(String url) {
        return (foodId, bytes) -> url;
    }

    private static final class RecordingNotifier {
        private final List<SyncChangedEvent> events = new CopyOnWriteArrayList<>();
        SyncChangeNotifier asNotifier() {
            return new SyncChangeNotifier(e -> events.add((SyncChangedEvent) e));
        }
        boolean notifiedNutritionFor(String userId) {
            return events.stream().anyMatch(e ->
                e.userId().equals(userId)
                    && e.collections().stream().anyMatch(c -> c.contains("nutrition")));
        }
    }

    private static <T> ObjectProvider<T> empty() {
        return provider(null);
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return require(); }
            @Override public T getObject() { return require(); }
            @Override public T getIfAvailable() { return bean; }
            @Override public T getIfUnique() { return bean; }
            private T require() {
                if (bean == null) throw new IllegalStateException("no bean");
                return bean;
            }
        };
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
            return Optional.empty();
        }
        @Override public void save(FoodEntry entry) { rows.put(key(entry.date(), entry.entryId()), entry); }
        @Override public void delete(String userId, LocalDate date, String entryId) { rows.remove(key(date, entryId)); }
    }
}
