package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * "Describe a meal": turn a free-text description into a logged meal, reusing a
 * previously-saved meal when the description matches one and otherwise creating
 * (and saving) a new one with AI-estimated macros and a generated studio photo.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #resolve} itemizes the description via {@link MealDescriptionAnalyzer},
 *       searches the shared {@link SavedMeal} catalog (the requesting user's own
 *       meals ranked first), asks the model to confirm a genuine match, and
 *       returns either the matched meal or a freshly created+saved one (whose
 *       photo is generating in the background). Nothing about the user's day
 *       changes here — it backs the preview endpoint.</li>
 *   <li>{@link #logResolvedMeal} logs a {@link SavedMeal} onto a day as a
 *       composite entry (full ingredient breakdown), attaching the saved meal's
 *       photo when it is already {@code READY} (reuse) or generating the entry's
 *       own finished-meal photo when it is not (a brand-new meal).</li>
 *   <li>{@link #logDescribed} is the one-shot convenience: resolve then log.</li>
 * </ol>
 *
 * <p>Lives in {@code core} and depends on the {@link MealDescriptionAnalyzer}
 * <em>port</em> via {@link ObjectProvider} so core unit tests construct it
 * without the integrations bean (matching {@link MealCaptureService}).
 */
@Service
public class MealDescriptionService {

    private static final int CANDIDATE_LIMIT = 8;

    private final ObjectProvider<MealDescriptionAnalyzer> analyzer;
    private final SavedMealRepository savedMeals;
    private final SavedMealImageService savedMealImages;
    private final NutritionService nutrition;
    private final FoodEntryImageService foodEntryImages;
    private final SyncChangeNotifier syncNotifier;

    public MealDescriptionService(
        ObjectProvider<MealDescriptionAnalyzer> analyzer,
        SavedMealRepository savedMeals,
        SavedMealImageService savedMealImages,
        NutritionService nutrition,
        FoodEntryImageService foodEntryImages,
        SyncChangeNotifier syncNotifier
    ) {
        this.analyzer = analyzer;
        this.savedMeals = savedMeals;
        this.savedMealImages = savedMealImages;
        this.nutrition = nutrition;
        this.foodEntryImages = foodEntryImages;
        this.syncNotifier = syncNotifier;
    }

    /**
     * Resolve a description to a concrete {@link SavedMeal}: an existing match
     * (when the catalog already holds the same dish) or a newly created+saved
     * one. The result carries the {@code matched} flag so the client can tell the
     * user "found your previous meal" vs "created a new one". Throws when meal
     * analysis is unavailable or the description yields no identifiable food.
     */
    public MealResolution resolve(String userId, String description) {
        requireUser(userId);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        MealDescriptionAnalyzer mealAnalyzer = analyzer.getIfAvailable();
        if (mealAnalyzer == null) {
            throw new IllegalStateException("meal description analysis is not available");
        }

        MealPhotoAnalyzer.MealAnalysis analysis = mealAnalyzer.analyze(description);
        List<MealPhotoAnalyzer.MealItem> items = cleanItems(analysis.items());
        if (items.isEmpty()) {
            throw new IllegalArgumentException("no identifiable food in description");
        }
        String mealName = firstNonBlank(analysis.mealName(), composeMealName(items), "Meal");

        // Look for a previously-saved meal of the same dish, user's own first.
        Optional<SavedMeal> match = findMatch(userId, mealAnalyzer, description, mealName);
        if (match.isPresent()) {
            return MealResolution.matched(match.get());
        }

        // No match — create, save and enqueue a studio photo so it's findable next time.
        List<CompositeIngredient> ingredients = toIngredients(items);
        SavedMeal created = createSavedMeal(userId, mealName, ingredients);
        return MealResolution.created(created);
    }

    /**
     * Log an already-resolved saved meal onto {@code date} as a composite entry,
     * reusing its full ingredient breakdown. The saved meal's photo is attached
     * when {@code READY}; otherwise the entry generates its own finished-meal
     * image (a brand-new meal whose saved-meal photo is still generating).
     */
    public FoodEntry logResolvedMeal(String userId, LocalDate date, MealType meal, String mealId) {
        requireUser(userId);
        SavedMeal saved = savedMeals.findById(mealId)
            .orElseThrow(() -> new IllegalArgumentException("saved meal not found: " + mealId));
        List<CompositeIngredient> ingredients = saved.ingredients();
        if (ingredients == null || ingredients.isEmpty()) {
            throw new IllegalArgumentException("saved meal has no ingredients: " + mealId);
        }
        FoodEntry entry = nutrition.addCompositeMeal(
            userId, date, meal, saved.name(), ingredients, EntrySource.MANUAL);

        if (saved.imageStatus() == FoodImageStatus.READY && saved.imageUrl() != null) {
            // Reuse: the photo already exists — attach it, no regeneration.
            nutrition.setEntryMealImage(
                userId, date, entry.entryId(), saved.imageUrl(), FoodImageStatus.READY);
        } else {
            // Brand-new meal: generate this entry's finished-meal photo (text-only).
            foodEntryImages.enqueueGeneration(userId, date, entry.entryId(), saved.name(), null);
        }
        syncNotifier.changed(userId, null, "nutritionDays/entries");
        return entry;
    }

    /** One-shot: resolve the description then log it onto {@code date}. */
    public FoodEntry logDescribed(String userId, LocalDate date, MealType meal, String description) {
        MealResolution resolution = resolve(userId, description);
        return logResolvedMeal(userId, date, meal, resolution.mealId());
    }

    /**
     * Fire-and-forget describe (the camera-capture pattern): immediately log an
     * {@code ANALYZING} placeholder named with the user's own description and
     * return it, then resolve + finalize off the request thread. The clients
     * poll the day view, so the placeholder fills in (or flips {@code FAILED})
     * without the user waiting on the Gemini round-trip.
     */
    public FoodEntry describeMealAsync(
        String userId, LocalDate date, MealType meal, String description) {
        requireUser(userId);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        if (analyzer.getIfAvailable() == null) {
            throw new IllegalStateException("meal description analysis is not available");
        }
        FoodEntry placeholder = nutrition.beginAnalyzingEntry(
            userId, date, meal, null, null,
            placeholderName(description), EntrySource.MANUAL);
        CompletableFuture.runAsync(
            () -> resolveAndFinalize(userId, date, placeholder.entryId(), description));
        return placeholder;
    }

    /**
     * Resolve the description and fill the placeholder in. Package-private and
     * synchronous so tests can drive it without the async hop. Never throws — a
     * failure marks the entry {@code FAILED}.
     */
    void resolveAndFinalize(String userId, LocalDate date, String entryId, String description) {
        try {
            MealResolution resolution = resolve(userId, description);
            FoodEntry entry = nutrition.finalizeCompositeMeal(
                userId, date, entryId, resolution.name(), resolution.ingredients());
            if (resolution.imageStatus() == FoodImageStatus.READY && resolution.imageUrl() != null) {
                nutrition.setEntryMealImage(
                    userId, date, entry.entryId(), resolution.imageUrl(), FoodImageStatus.READY);
            } else {
                foodEntryImages.enqueueGeneration(
                    userId, date, entry.entryId(), resolution.name(), null);
            }
        } catch (RuntimeException e) {
            nutrition.markAnalysisFailed(userId, date, entryId);
        } finally {
            // The placeholder transitioned (READY/FAILED) — wake the user's
            // devices. origin=null: the resolution ran server-side, so even the
            // describing device should refresh.
            syncNotifier.changed(userId, null, "nutritionDays/entries");
        }
    }

    /** First ~60 chars of the description, single-line, as the pending row's name. */
    private static String placeholderName(String description) {
        String oneLine = description.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "…";
    }

    // ---- internals ----

    private Optional<SavedMeal> findMatch(
        String userId, MealDescriptionAnalyzer mealAnalyzer, String description, String mealName) {
        List<SavedMeal> candidates = searchUserFirst(userId, mealName);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<MealDescriptionAnalyzer.MealCandidate> offered = candidates.stream()
            .map(m -> new MealDescriptionAnalyzer.MealCandidate(m.mealId(), m.name()))
            .toList();
        Optional<String> chosen = mealAnalyzer.matchMeal(description, offered);
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream().filter(m -> m.mealId().equals(chosen.get())).findFirst();
    }

    /**
     * Saved meals matching {@code query} for the add-food search, the requesting
     * user's own meals first. Reuses the same leading-token prefix search the
     * describe matcher uses, so the add flow surfaces full meals alongside
     * catalog ingredients. Empty when the query has no usable prefix.
     */
    public List<SavedMeal> searchSavedMeals(String userId, String query) {
        return searchUserFirst(userId, query);
    }

    /**
     * Candidate saved meals for the name, the requesting user's own meals first.
     * Searches on the meal name's leading token so "Chicken, rice and broccoli"
     * still finds "Chicken and rice".
     */
    private List<SavedMeal> searchUserFirst(String userId, String mealName) {
        String prefix = searchPrefix(mealName);
        if (prefix.isBlank()) {
            return List.of();
        }
        List<SavedMeal> all = savedMeals.searchByNamePrefix(prefix, CANDIDATE_LIMIT);
        List<SavedMeal> mine = new ArrayList<>();
        List<SavedMeal> others = new ArrayList<>();
        for (SavedMeal m : all) {
            if (userId.equals(m.createdBy())) {
                mine.add(m);
            } else {
                others.add(m);
            }
        }
        mine.addAll(others);
        return mine;
    }

    /** Leading word of the name, lower-cased — the catalog prefix key. */
    private static String searchPrefix(String mealName) {
        if (mealName == null) return "";
        String trimmed = mealName.strip().toLowerCase();
        int comma = trimmed.indexOf(',');
        if (comma > 0) {
            trimmed = trimmed.substring(0, comma);
        }
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    private SavedMeal createSavedMeal(
        String userId, String mealName, List<CompositeIngredient> ingredients) {
        Macros total = Macros.zero();
        double grams = 0.0;
        for (CompositeIngredient ing : ingredients) {
            total = total.plus(ing.macros());
            if (ing.servingGrams() != null) {
                grams += ing.servingGrams() * (ing.quantity() != null ? ing.quantity() : 1.0);
            }
        }
        SavedMeal meal = new SavedMeal(
            UUID.randomUUID().toString(), mealName, mealName.toLowerCase(), userId,
            List.copyOf(ingredients), grams, total, FoodSource.GEMINI_DESCRIPTION,
            null, FoodImageStatus.NONE, null, null);
        savedMeals.save(meal);
        savedMealImages.enqueueGeneration(meal.mealId());
        return meal;
    }

    /**
     * Build composite ingredients from the extracted items. Described-meal
     * ingredients carry no catalog {@code foodId} (no per-ingredient image is
     * generated — the meal gets a single plated photo), but keep their per-100g
     * baseline so portions stay editable.
     */
    private static List<CompositeIngredient> toIngredients(List<MealPhotoAnalyzer.MealItem> items) {
        List<CompositeIngredient> ingredients = new ArrayList<>(items.size());
        for (MealPhotoAnalyzer.MealItem item : items) {
            double grams = item.estimatedPortionGrams() != null && item.estimatedPortionGrams() > 0
                ? item.estimatedPortionGrams() : 100.0;
            Macros per100g = item.macrosPer100g();
            Macros portion = per100g != null ? per100g.scale(grams / 100.0) : Macros.zero();
            ingredients.add(new CompositeIngredient(
                item.name(), null, per100g, grams, gramsLabel(grams), 1.0, portion));
        }
        return ingredients;
    }

    private static List<MealPhotoAnalyzer.MealItem> cleanItems(List<MealPhotoAnalyzer.MealItem> raw) {
        List<MealPhotoAnalyzer.MealItem> items = new ArrayList<>();
        if (raw == null) return items;
        for (MealPhotoAnalyzer.MealItem item : raw) {
            if (item != null && item.name() != null && !item.name().isBlank()) {
                items.add(item);
            }
        }
        return items;
    }

    private static String gramsLabel(double grams) {
        return Math.round(grams > 0 ? grams : 100.0) + " g";
    }

    private static String composeMealName(List<MealPhotoAnalyzer.MealItem> items) {
        List<String> names = items.stream().map(MealPhotoAnalyzer.MealItem::name).toList();
        return switch (names.size()) {
            case 0 -> "Meal";
            case 1 -> names.get(0);
            case 2 -> names.get(0) + " & " + names.get(1);
            default -> String.join(", ", names.subList(0, names.size() - 1))
                + " & " + names.get(names.size() - 1);
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    /**
     * The outcome of resolving a description: a concrete saved meal, plus whether
     * it was an existing match ({@code matched=true}) or freshly created.
     *
     * @param mealId    the saved meal's id (existing or new)
     * @param matched   true when an existing saved meal was reused
     * @param name      the meal name
     * @param ingredients the full ingredient breakdown
     * @param totalGrams  total grams for one serving
     * @param macros      summed portion macros for one serving
     * @param imageUrl    the studio photo url (may be null while generating)
     * @param imageStatus the photo lifecycle status
     */
    public record MealResolution(
        String mealId,
        boolean matched,
        String name,
        List<CompositeIngredient> ingredients,
        Double totalGrams,
        Macros macros,
        String imageUrl,
        FoodImageStatus imageStatus
    ) {
        static MealResolution matched(SavedMeal m) {
            return new MealResolution(m.mealId(), true, m.name(), m.ingredients(),
                m.totalGrams(), m.macros(), m.imageUrl(), m.imageStatus());
        }

        static MealResolution created(SavedMeal m) {
            return new MealResolution(m.mealId(), false, m.name(), m.ingredients(),
                m.totalGrams(), m.macros(), m.imageUrl(), m.imageStatus());
        }
    }
}
