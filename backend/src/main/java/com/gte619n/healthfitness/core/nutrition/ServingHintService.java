package com.gte619n.healthfitness.core.nutrition;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Produces a short, everyday-language "typical serving" explanation for a logged
 * entry — e.g. "About ¾ cup of blueberries (110 g)" — so the amount is easy to
 * picture in the edit sheet. Generated lazily the first time an entry is viewed
 * and cached by subject (name + serving + rounded weight + components), so an
 * identical food is only ever described once regardless of user. Reuses the
 * durable text cache on {@link FoodImageStore} rather than adding a field to the
 * entry/food records or new storage.
 *
 * <p>Both ports are optional ({@link ObjectProvider}): with no analyzer bean
 * (core-only tests, capture disabled) this yields no hint and the client simply
 * shows nothing.
 */
@Service
public class ServingHintService {

    /** Namespace for the serving-hint entries in the shared text cache. */
    static final String CACHE_NAMESPACE = "serving-hints";
    private static final String CACHE_VERSION = "v1";
    /** Round the logged weight into 5 g buckets so near-identical portions share a hint. */
    private static final int GRAMS_BUCKET = 5;

    private final FoodEntryRepository entries;
    private final ObjectProvider<ServingHintAnalyzer> analyzer;
    private final ObjectProvider<FoodImageStore> store;

    public ServingHintService(
        FoodEntryRepository entries,
        ObjectProvider<ServingHintAnalyzer> analyzer,
        ObjectProvider<FoodImageStore> store
    ) {
        this.entries = entries;
        this.analyzer = analyzer;
        this.store = store;
    }

    /**
     * The typical-serving explanation for one logged entry, or empty when the
     * analyzer is unavailable or the entry is gone. Cache hit → no Gemini call.
     */
    public Optional<String> hintForEntry(String userId, LocalDate date, String entryId) {
        ServingHintAnalyzer generator = analyzer.getIfAvailable();
        if (generator == null) {
            return Optional.empty();
        }
        Optional<FoodEntry> found = entries.findById(userId, date, entryId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FoodEntry entry = found.get();

        Long gramsBucket = bucketedGrams(entry);
        Double roundedGrams = gramsBucket == null ? null : gramsBucket.doubleValue();
        List<String> components = entry.isComposite()
            ? entry.ingredients().stream()
                .map(CompositeIngredient::name)
                .filter(Objects::nonNull)
                .toList()
            : List.of();

        String cacheKey = cacheKey(entry.foodName(), entry.servingLabel(), gramsBucket, components);
        FoodImageStore cache = store.getIfAvailable();
        if (cache != null) {
            Optional<String> cached = cache.findCachedText(CACHE_NAMESPACE, cacheKey);
            if (cached.isPresent()) {
                return cached;
            }
        }

        Optional<String> hint =
            generator.describeServing(entry.foodName(), roundedGrams, entry.servingLabel(), components);
        if (hint.isPresent() && cache != null) {
            cache.putCachedText(CACHE_NAMESPACE, cacheKey, hint.get());
        }
        return hint;
    }

    /** Logged grams (serving × quantity), rounded to a {@link #GRAMS_BUCKET} bucket, or null. */
    private static Long bucketedGrams(FoodEntry entry) {
        if (entry.servingGrams() == null) {
            return null;
        }
        double qty = entry.quantity() == null ? 1.0 : entry.quantity();
        double grams = entry.servingGrams() * qty;
        if (grams <= 0) {
            return null;
        }
        return Math.round(grams / GRAMS_BUCKET) * (long) GRAMS_BUCKET;
    }

    private static String cacheKey(
        String name, String servingLabel, Long gramsBucket, List<String> components) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        String l = servingLabel == null ? "" : servingLabel.strip().toLowerCase(Locale.ROOT);
        String c = String.join(",", components).toLowerCase(Locale.ROOT);
        return String.join(
            "|", CACHE_VERSION, n, l, gramsBucket == null ? "" : gramsBucket.toString(), c);
    }
}
