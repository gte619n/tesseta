package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * "Adjust with AI": correct an already-logged meal from a free-text instruction
 * (e.g. "that's pearl couscous, not lentils"). The flow is preview-then-confirm:
 *
 * <ol>
 *   <li>{@link #preview} runs {@link MealAdjustmentAnalyzer} against the current
 *       entry (+ the original photo when there is one) and returns the revised
 *       meal as a <strong>proposal only</strong> — nothing is persisted, so the
 *       client can show the before/after diff and let the user accept or discard.</li>
 *   <li>{@link #apply} persists an accepted proposal onto the entry (reusing the
 *       finalize paths the capture flow uses), regenerates the finished-meal
 *       image, and — when asked — saves the corrected meal to the shared catalog
 *       so it's right next time.</li>
 * </ol>
 *
 * <p>Lives in {@code core} and depends on the {@link MealAdjustmentAnalyzer} and
 * {@link MealPhotoReader} <em>ports</em> via {@link ObjectProvider}, so core unit
 * tests construct it without the integrations beans (matching
 * {@link MealCaptureService}). When the analyzer bean is absent it raises
 * {@link IllegalStateException}, which the controller maps to a 422.
 */
@Service
public class MealAdjustmentService {

    private final ObjectProvider<MealAdjustmentAnalyzer> analyzer;
    private final ObjectProvider<MealPhotoReader> photoReader;
    private final NutritionService nutrition;
    private final FoodCatalogService catalog;
    private final FoodEntryImageService foodEntryImages;
    private final MealDescriptionService mealDescription;
    private final SyncChangeNotifier syncNotifier;

    public MealAdjustmentService(
        ObjectProvider<MealAdjustmentAnalyzer> analyzer,
        ObjectProvider<MealPhotoReader> photoReader,
        NutritionService nutrition,
        FoodCatalogService catalog,
        FoodEntryImageService foodEntryImages,
        MealDescriptionService mealDescription,
        SyncChangeNotifier syncNotifier
    ) {
        this.analyzer = analyzer;
        this.photoReader = photoReader;
        this.nutrition = nutrition;
        this.catalog = catalog;
        this.foodEntryImages = foodEntryImages;
        this.mealDescription = mealDescription;
        this.syncNotifier = syncNotifier;
    }

    /**
     * Run the correction and return the revised meal as a non-persisted proposal.
     * Reads the original photo back from storage (when the entry has one) so the
     * model can verify portions and untouched items; falls back to text-only for
     * described meals. Throws {@link IllegalArgumentException} when the entry is
     * unknown, {@link IllegalStateException} when adjustment is unavailable, and
     * lets the analyzer's extraction failure propagate (mapped to 422).
     */
    public AdjustmentProposal preview(
        String userId, LocalDate date, String entryId, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction is required");
        }
        MealAdjustmentAnalyzer adjuster = analyzer.getIfAvailable();
        if (adjuster == null) {
            throw new IllegalStateException("meal adjustment is not available");
        }
        FoodEntry entry = nutrition.findEntry(userId, date, entryId)
            .orElseThrow(() -> new IllegalArgumentException("entry not found: " + entryId));

        MealAdjustmentAnalyzer.MealContext context = contextOf(entry);

        byte[] photoBytes = null;
        String mime = null;
        if (entry.photoRef() != null && !entry.photoRef().isBlank()) {
            MealPhotoReader reader = photoReader.getIfAvailable();
            if (reader != null) {
                Optional<MealPhotoReader.Photo> photo = reader.read(entry.photoRef());
                if (photo.isPresent()) {
                    photoBytes = photo.get().bytes();
                    mime = photo.get().mimeType();
                }
            }
        }

        MealPhotoAnalyzer.MealAnalysis revised = adjuster.adjust(context, instruction, photoBytes, mime);
        List<MealPhotoAnalyzer.MealItem> items = cleanItems(revised.items());
        if (items.isEmpty()) {
            throw new IllegalStateException("adjustment produced no identifiable food");
        }

        List<ProposalItem> proposed = new ArrayList<>(items.size());
        Macros newTotal = Macros.zero();
        for (MealPhotoAnalyzer.MealItem item : items) {
            double grams = item.estimatedPortionGrams() != null && item.estimatedPortionGrams() > 0
                ? item.estimatedPortionGrams() : 100.0;
            Macros per100g = item.macrosPer100g();
            Macros portion = per100g != null
                ? per100g.scale(grams / 100.0).withDerivedCalories() : Macros.zero();
            newTotal = newTotal.plus(portion);
            proposed.add(new ProposalItem(
                item.name(), gramsLabel(grams), grams,
                per100g != null ? per100g.withDerivedCalories() : null, portion));
        }

        String mealName = firstNonBlank(revised.mealName(), composeMealName(items), entry.foodName());
        return new AdjustmentProposal(
            mealName, revised.packagedProduct(), proposed,
            newTotal.withDerivedCalories(),
            entry.macros() != null ? entry.macros() : Macros.zero());
    }

    /**
     * Persist an accepted proposal onto the entry. A single packaged product with
     * one item finalizes as a catalog-backed single food; anything else finalizes
     * as a composite meal (reusing an existing ingredient's catalog food when the
     * name is unchanged, minting one otherwise) and regenerates the finished-meal
     * image. When {@code saveAsMeal} is set, the corrected composite meal is also
     * saved to the shared catalog for reuse. Fans out a sync change and returns
     * the updated entry.
     */
    public FoodEntry apply(
        String userId, LocalDate date, String entryId,
        AcceptedAdjustment accepted, boolean saveAsMeal) {
        if (accepted == null || accepted.items() == null || accepted.items().isEmpty()) {
            throw new IllegalArgumentException("at least one item is required");
        }
        FoodEntry entry = nutrition.findEntry(userId, date, entryId)
            .orElseThrow(() -> new IllegalArgumentException("entry not found: " + entryId));
        String mealName = firstNonBlank(accepted.mealName(), entry.foodName(), "Meal");

        boolean singleProduct = accepted.packagedProduct() && accepted.items().size() == 1;
        FoodEntry updated;
        if (singleProduct) {
            AcceptedItem item = accepted.items().get(0);
            double grams = item.servingGrams() != null && item.servingGrams() > 0
                ? item.servingGrams() : 100.0;
            String label = item.servingLabel() != null ? item.servingLabel() : gramsLabel(grams);
            Macros portion = item.macros() != null
                ? item.macros()
                : (item.macrosPer100g() != null ? item.macrosPer100g().scale(grams / 100.0) : Macros.zero());
            CatalogFood food = catalog.create(
                userId, item.name(), null, null, "product", item.macrosPer100g(),
                List.of(new ServingSize(label, grams)), 0, FoodSource.GEMINI_PHOTO, entry.photoRef());
            updated = nutrition.finalizeSingleFood(
                userId, date, entryId, food.foodId(), item.name(), label, grams, 1.0, portion);
        } else {
            List<CompositeIngredient> ingredients = toIngredients(userId, entry, accepted.items());
            updated = nutrition.finalizeCompositeMeal(userId, date, entryId, mealName, ingredients);
            // Contents changed, so the finished-meal image no longer matches —
            // regenerate it from the corrected name + the original capture photo.
            foodEntryImages.enqueueGeneration(userId, date, entryId, mealName, entry.photoRef());
            if (saveAsMeal) {
                mealDescription.saveMeal(userId, mealName, ingredients);
            }
        }
        syncNotifier.changed(userId, null, "nutritionDays/entries");
        return updated;
    }

    // ---- internals ----

    private static MealAdjustmentAnalyzer.MealContext contextOf(FoodEntry entry) {
        List<MealAdjustmentAnalyzer.MealContext.Item> items = new ArrayList<>();
        if (entry.isComposite()) {
            for (CompositeIngredient ing : entry.ingredients()) {
                items.add(new MealAdjustmentAnalyzer.MealContext.Item(
                    ing.name(), ing.servingGrams(), ing.macrosPer100g()));
            }
            return new MealAdjustmentAnalyzer.MealContext(entry.foodName(), false, items);
        }
        // Single food: the stored macros are the portion snapshot — derive a
        // per-100 g baseline from the serving weight so the model gets the same
        // shape a composite ingredient has.
        Double grams = entry.servingGrams();
        Macros per100g = entry.macros();
        if (grams != null && grams > 0 && entry.macros() != null) {
            double q = entry.quantity() != null && entry.quantity() > 0 ? entry.quantity() : 1.0;
            per100g = entry.macros().scale(100.0 / (grams * q));
        }
        items.add(new MealAdjustmentAnalyzer.MealContext.Item(entry.foodName(), grams, per100g));
        return new MealAdjustmentAnalyzer.MealContext(entry.foodName(), true, items);
    }

    /**
     * Map accepted items to composite ingredients, reusing the catalog food (and
     * its raw-ingredient image) of an existing ingredient whose name is unchanged
     * and minting a new {@code GEMINI_PHOTO} catalog food for anything new.
     */
    private List<CompositeIngredient> toIngredients(
        String userId, FoodEntry entry, List<AcceptedItem> items) {
        List<CompositeIngredient> existing = entry.ingredients() != null
            ? entry.ingredients() : List.of();
        List<CompositeIngredient> out = new ArrayList<>(items.size());
        for (AcceptedItem item : items) {
            double grams = item.servingGrams() != null && item.servingGrams() > 0
                ? item.servingGrams() : 100.0;
            String label = item.servingLabel() != null ? item.servingLabel() : gramsLabel(grams);
            Macros per100g = item.macrosPer100g();
            Macros portion = item.macros() != null
                ? item.macros()
                : (per100g != null ? per100g.scale(grams / 100.0) : Macros.zero());
            String foodId = existing.stream()
                .filter(e -> e.name() != null && e.name().equalsIgnoreCase(item.name()))
                .map(CompositeIngredient::foodId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElseGet(() -> catalog.create(
                    userId, item.name(), null, null, "ingredient", per100g,
                    List.of(new ServingSize(label, grams)), 0,
                    FoodSource.GEMINI_PHOTO, null).foodId());
            out.add(new CompositeIngredient(item.name(), foodId, per100g, grams, label, 1.0, portion));
        }
        return out;
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

    /**
     * A non-persisted correction proposal: the revised meal plus the before/after
     * day-total macros so the client can render the diff.
     */
    public record AdjustmentProposal(
        String mealName,
        boolean packagedProduct,
        List<ProposalItem> items,
        Macros newTotals,
        Macros oldTotals
    ) {}

    /** One proposed component, with its portion + per-100 g macros. */
    public record ProposalItem(
        String name,
        String servingLabel,
        Double servingGrams,
        Macros macrosPer100g,
        Macros macros
    ) {}

    /** The proposal the client accepted and sends back to {@link #apply}. */
    public record AcceptedAdjustment(
        String mealName,
        boolean packagedProduct,
        List<AcceptedItem> items
    ) {}

    /** One accepted component (mirrors {@link ProposalItem}). */
    public record AcceptedItem(
        String name,
        String servingLabel,
        Double servingGrams,
        Macros macrosPer100g,
        Macros macros
    ) {}
}
