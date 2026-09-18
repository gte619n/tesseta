package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
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

    private static final Logger log = LoggerFactory.getLogger(MealAdjustmentService.class);

    /** Data-message type the Android client switches on to open the adjust review screen. */
    public static final String NOTIF_ADJUST_REVIEW = "adjust-review";
    /** Data-message type the Android client switches on when the adjustment failed. */
    public static final String NOTIF_ADJUST_FAILED = "adjust-failed";

    private final ObjectProvider<MealAdjustmentAnalyzer> analyzer;
    private final ObjectProvider<MealPhotoReader> photoReader;
    private final NutritionService nutrition;
    private final FoodCatalogService catalog;
    private final FoodEntryImageService foodEntryImages;
    private final MealDescriptionService mealDescription;
    private final SyncChangeNotifier syncNotifier;
    private final ObjectProvider<NutritionJobQueue> jobQueue;
    private final AdjustReviewPublisher reviewPublisher;

    public MealAdjustmentService(
        ObjectProvider<MealAdjustmentAnalyzer> analyzer,
        ObjectProvider<MealPhotoReader> photoReader,
        NutritionService nutrition,
        FoodCatalogService catalog,
        FoodEntryImageService foodEntryImages,
        MealDescriptionService mealDescription,
        SyncChangeNotifier syncNotifier,
        ObjectProvider<NutritionJobQueue> jobQueue,
        AdjustReviewPublisher reviewPublisher
    ) {
        this.analyzer = analyzer;
        this.photoReader = photoReader;
        this.nutrition = nutrition;
        this.catalog = catalog;
        this.foodEntryImages = foodEntryImages;
        this.mealDescription = mealDescription;
        this.syncNotifier = syncNotifier;
        this.jobQueue = jobQueue;
        this.reviewPublisher = reviewPublisher;
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
        return computeProposal(adjuster, entry, instruction);
    }

    /**
     * Run the analyzer against {@code entry} (+ its photo when present) and build
     * the revised meal as a proposal. Shared by the synchronous {@link #preview}
     * and the async {@link #runAdjustment}. Throws {@link IllegalStateException}
     * when the analyzer returns no identifiable food; lets extraction failures
     * propagate.
     */
    private AdjustmentProposal computeProposal(
        MealAdjustmentAnalyzer adjuster, FoodEntry entry, String instruction) {
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

    // ----- Async "Adjust with AI" (background job + FCM review) ---------

    /**
     * Begin an async adjustment: flip the entry to {@code ADJUSTING} (storing the
     * instruction + saveAsMeal), then enqueue a durable {@code MEAL_ADJUSTMENT}
     * job — returning immediately. Falls back to an inline off-thread run when no
     * durable queue is wired (dev / core test). Wakes devices to render the
     * "Adjusting…" state. Throws {@link IllegalArgumentException} on a blank
     * instruction / unknown entry and {@link IllegalStateException} when the
     * analyzer is unavailable (mapped to 422).
     */
    public FoodEntry startAdjustment(
        String userId, LocalDate date, String entryId, String instruction, boolean saveAsMeal) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction is required");
        }
        if (analyzer.getIfAvailable() == null) {
            throw new IllegalStateException("meal adjustment is not available");
        }
        FoodEntry adjusting = nutrition.beginAdjustment(userId, date, entryId, instruction, saveAsMeal);
        NutritionJobQueue queue = jobQueue.getIfAvailable();
        if (queue != null) {
            queue.enqueue(NutritionJob.mealAdjustment(userId, date.toString(), entryId));
        } else {
            CompletableFuture.runAsync(() -> runAdjustment(userId, date, entryId));
        }
        syncNotifier.changed(userId, null, "nutritionDays/entries");
        return adjusting;
    }

    /**
     * Durable-queue entry point: run the adjustment for an entry still
     * {@code ADJUSTING}. Idempotent — a redelivered job whose entry has already
     * settled (PENDING_REVIEW/REJECTED/cleared) is a cheap no-op.
     */
    public void adjustFromStateOrThrow(String userId, LocalDate date, String entryId) {
        if (analyzer.getIfAvailable() == null) {
            return;
        }
        Optional<FoodEntry> found = nutrition.findEntry(userId, date, entryId);
        if (found.isEmpty() || found.get().adjustment() == null
            || found.get().adjustment().status() != AdjustStatus.ADJUSTING) {
            return;
        }
        runAdjustment(userId, date, entryId);
    }

    /** Mark an adjustment failed from the queue (retries exhausted). */
    public void markFailed(String userId, LocalDate date, String entryId) {
        nutrition.rejectAdjustment(userId, date, entryId);
        publishReview(userId, date, entryId, true, 0.0);
        syncNotifier.changed(userId, null, "nutritionDays/entries");
    }

    /**
     * Run the re-analysis and settle the pass. Package-private + synchronous so
     * tests drive it without the async hop. Never throws — a failure rejects it.
     */
    void runAdjustment(String userId, LocalDate date, String entryId) {
        try {
            MealAdjustmentAnalyzer adjuster = analyzer.getIfAvailable();
            FoodEntry entry = nutrition.findEntry(userId, date, entryId).orElse(null);
            if (adjuster == null || entry == null || entry.adjustment() == null
                || entry.adjustment().status() != AdjustStatus.ADJUSTING) {
                return;
            }
            AdjustmentProposal proposal =
                computeProposal(adjuster, entry, entry.adjustment().instruction());
            nutrition.setAdjustmentProposal(userId, date, entryId, proposal);
            double deltaKcal = kcal(proposal.newTotals()) - kcal(proposal.oldTotals());
            publishReview(userId, date, entryId, false, deltaKcal);
        } catch (RuntimeException e) {
            log.warn("Meal adjustment failed for entry {}: {}", entryId, e.getMessage());
            nutrition.rejectAdjustment(userId, date, entryId);
            publishReview(userId, date, entryId, true, 0.0);
        } finally {
            syncNotifier.changed(userId, null, "nutritionDays/entries");
        }
    }

    /**
     * Commit the pending proposal (the "Apply" path, from the review sheet or the
     * notification action): persist the stored proposal onto the entry via
     * {@link #apply}, then clear the adjustment state. Requires a
     * {@code PENDING_REVIEW} proposal. The stored {@code saveAsMeal} choice is
     * honored unless {@code saveAsMealOverride} is non-null — the review sheet
     * lets the user change their mind after seeing the diff, while the bodiless
     * notification-action commit passes null and keeps the submit-time choice.
     */
    public FoodEntry commit(String userId, LocalDate date, String entryId) {
        return commit(userId, date, entryId, null);
    }

    /** As {@link #commit(String, LocalDate, String)} with the review-time override. */
    public FoodEntry commit(
        String userId, LocalDate date, String entryId, Boolean saveAsMealOverride) {
        FoodEntry entry = nutrition.findEntry(userId, date, entryId)
            .orElseThrow(() -> new IllegalArgumentException("entry not found: " + entryId));
        MealAdjustment adj = entry.adjustment();
        if (adj == null || adj.status() != AdjustStatus.PENDING_REVIEW || adj.proposal() == null) {
            throw new IllegalStateException("no adjustment proposal to commit: " + entryId);
        }
        AdjustmentProposal p = adj.proposal();
        AcceptedAdjustment accepted = new AcceptedAdjustment(
            p.mealName(), p.packagedProduct(),
            p.items().stream().map(it -> new AcceptedItem(
                it.name(), it.servingLabel(), it.servingGrams(),
                it.macrosPer100g(), it.macros())).toList());
        apply(userId, date, entryId, accepted,
            saveAsMealOverride != null ? saveAsMealOverride : adj.saveAsMeal());
        // apply() rebuilt the entry via the finalize paths (which preserve the
        // adjustment) — clear it now so the committed entry carries no pending state.
        FoodEntry cleared = nutrition.discardAdjustment(userId, date, entryId).orElse(null);
        syncNotifier.changed(userId, null, "nutritionDays/entries");
        return cleared != null ? cleared
            : nutrition.findEntry(userId, date, entryId).orElse(entry);
    }

    /** Discard a pending/rejected adjustment (the "Discard" path). */
    public Optional<FoodEntry> discard(String userId, LocalDate date, String entryId) {
        Optional<FoodEntry> updated = nutrition.discardAdjustment(userId, date, entryId);
        updated.ifPresent(e -> syncNotifier.changed(userId, null, "nutritionDays/entries"));
        return updated;
    }

    /**
     * Publish the review/failed event — a listener ({@code AdjustReviewNotifier})
     * turns it into the FCM push. Never throws; the in-app pending-review state is
     * the durable path.
     */
    private void publishReview(
        String userId, LocalDate date, String entryId, boolean rejected, double deltaKcal) {
        try {
            reviewPublisher.reviewReady(new AdjustReviewReadyEvent(
                userId, date.toString(), entryId, rejected, deltaKcal));
        } catch (RuntimeException e) {
            log.debug("Adjust review event failed for user {}: {}", userId, e.getMessage());
        }
    }

    private static double kcal(Macros m) {
        return m == null || m.caloriesKcal() == null ? 0.0 : m.caloriesKcal();
    }

    /**
     * Persist an accepted proposal onto the entry. A single packaged product with
     * one item finalizes as a catalog-backed single food; anything else finalizes
     * as a composite meal (reusing an existing ingredient's catalog food when the
     * name is unchanged, minting one otherwise) and regenerates the finished-meal
     * image. When {@code saveAsMeal} is set the correction also reaches the
     * source: a composite meal is saved to the shared catalog for reuse, and a
     * single product updates the entry's own catalog food in place when the user
     * created it (falling back to minting a fresh food otherwise). Fans out a
     * sync change and returns the updated entry.
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
            List<ServingSize> servings = List.of(new ServingSize(label, grams));
            CatalogFood food = saveAsMeal
                ? catalog.correctOwnFood(entry.foodId(), userId, item.name(),
                    item.macrosPer100g(), servings, entry.photoRef()).orElse(null)
                : null;
            if (food == null) {
                food = catalog.create(
                    userId, item.name(), null, null, "product", item.macrosPer100g(),
                    servings, 0, FoodSource.GEMINI_PHOTO, entry.photoRef());
            }
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
                // A genuinely new ingredient: reuse an existing catalog food of the
                // same name if one exists (creation-time de-dup) instead of minting
                // a duplicate; the logged macros ride on the CompositeIngredient.
                .orElseGet(() -> catalog.resolveOrCreate(
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
