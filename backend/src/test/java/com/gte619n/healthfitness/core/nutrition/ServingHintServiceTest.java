package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit-tests {@link ServingHintService}: a hint is generated once, cached by
 * subject, and served from the cache on the next view (no second Gemini call);
 * with no analyzer bean it degrades to no hint.
 */
class ServingHintServiceTest {

    private static final String USER = "u1";
    private static final LocalDate DATE = LocalDate.of(2026, 8, 3);

    @Test
    void generatesOnMiss_thenServesFromCacheWithoutRegenerating() {
        InMemEntries entries = new InMemEntries();
        entries.save(entry("e1", "Blueberries", 92.0));
        CountingAnalyzer analyzer = new CountingAnalyzer(Optional.of("About ¾ cup of blueberries (90 g)."));
        TextCacheStore store = new TextCacheStore();
        ServingHintService svc = new ServingHintService(entries, provider(analyzer), provider(store));

        Optional<String> first = svc.hintForEntry(USER, DATE, "e1");
        Optional<String> second = svc.hintForEntry(USER, DATE, "e1");

        assertEquals(Optional.of("About ¾ cup of blueberries (90 g)."), first);
        assertEquals(first, second, "identical entry reuses the cached hint");
        assertEquals(1, analyzer.calls, "the second view is served from cache, not regenerated");
        assertTrue(store.written, "the generated hint is persisted to the cache");
    }

    @Test
    void withoutAnalyzer_yieldsNoHint() {
        InMemEntries entries = new InMemEntries();
        entries.save(entry("e1", "Blueberries", 92.0));
        ServingHintService svc = new ServingHintService(entries, provider(null), provider(new TextCacheStore()));

        assertEquals(Optional.empty(), svc.hintForEntry(USER, DATE, "e1"));
    }

    @Test
    void missingEntry_yieldsNoHint() {
        InMemEntries entries = new InMemEntries();
        CountingAnalyzer analyzer = new CountingAnalyzer(Optional.of("x"));
        ServingHintService svc = new ServingHintService(entries, provider(analyzer), provider(new TextCacheStore()));

        assertEquals(Optional.empty(), svc.hintForEntry(USER, DATE, "gone"));
        assertEquals(0, analyzer.calls);
    }

    // ---- fakes ----

    private static FoodEntry entry(String entryId, String name, double grams) {
        return new FoodEntry(
            USER, DATE, entryId, MealType.SNACK, "food-" + entryId, name, "1 serving", grams, 1.0,
            new Macros(92.0, 1.0, 22.0, 0.5, 0.0, 0.0), null, null, EntrySource.MANUAL,
            null, null, FoodImageStatus.READY, EntryAnalysisStatus.NONE, Instant.now(), Instant.now());
    }

    private static final class CountingAnalyzer implements ServingHintAnalyzer {
        private final Optional<String> result;
        int calls;
        CountingAnalyzer(Optional<String> result) { this.result = result; }
        @Override public Optional<String> describeServing(
            String name, Double grams, String servingLabel, List<String> components) {
            calls++;
            return result;
        }
    }

    private static final class TextCacheStore implements FoodImageStore {
        private final Map<String, String> cache = new ConcurrentHashMap<>();
        boolean written;
        @Override public String upload(String foodId, byte[] imageBytes) { return "unused"; }
        @Override public Optional<String> findCachedText(String namespace, String cacheKey) {
            return Optional.ofNullable(cache.get(namespace + "|" + cacheKey));
        }
        @Override public void putCachedText(String namespace, String cacheKey, String value) {
            written = true;
            cache.put(namespace + "|" + cacheKey, value);
        }
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
