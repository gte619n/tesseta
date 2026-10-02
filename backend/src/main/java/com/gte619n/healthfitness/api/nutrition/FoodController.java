package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.api.support.RequestTimeZone;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogService;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.gte619n.healthfitness.core.nutrition.NutritionService;
import com.gte619n.healthfitness.core.nutrition.ServingSize;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Global, authenticated (not user-scoped) food catalog API. */
@RestController
@RequestMapping("/api/foods")
public class FoodController {

    /** How far back we look at the user's history to learn their meal-time habits. */
    private static final int MEAL_BIAS_DAYS = 30;

    private final CurrentUserProvider currentUser;
    private final FoodCatalogService catalog;
    private final NutritionService nutrition;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public FoodController(
        CurrentUserProvider currentUser,
        FoodCatalogService catalog,
        NutritionService nutrition,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier) {
        this.currentUser = currentUser;
        this.catalog = catalog;
        this.nutrition = nutrition;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    /**
     * Name/token search over the shared catalog. When the caller passes the
     * {@code meal} they're about to log (the client's time-of-day guess), results
     * are biased so the foods this user actually logs at that meal float to the
     * top — e.g. at breakfast your usual breakfast foods lead the list — without
     * disturbing the underlying relevance order within each group. The bias is
     * purely a re-rank; it never adds or drops results.
     */
    @GetMapping("/search")
    public List<FoodResponse> search(
        @RequestParam(value = "q", required = false) String q,
        @RequestParam(value = "meal", required = false) MealType meal,
        @RequestHeader(value = RequestTimeZone.HEADER, required = false) String timezone) {
        List<CatalogFood> results = catalog.search(q);
        if (meal != null && !results.isEmpty()) {
            results = biasByPreferred(results, preferredFoodIds(meal, timezone));
        }
        return results.stream().map(FoodResponse::from).toList();
    }

    /**
     * The catalog-food ids this user has logged at {@code meal} within the last
     * {@link #MEAL_BIAS_DAYS} days — their habitual foods for that time of day.
     * Manual/composite entries (no {@code foodId}) can't be matched back to a
     * catalog search result, so they're skipped.
     */
    private Set<String> preferredFoodIds(MealType meal, String timezone) {
        String userId = currentUser.get().userId();
        LocalDate today = LocalDate.now(RequestTimeZone.resolve(timezone));
        return nutrition.listRecentEntries(userId, today, MEAL_BIAS_DAYS).stream()
            .filter(e -> e.meal() == meal)
            .map(FoodEntry::foodId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());
    }

    /**
     * Stable partition: foods in {@code preferred} first, everyone else after,
     * each group keeping the catalog's relevance order (a stable sort on a
     * 0/1 key). A no-op when nothing is preferred.
     */
    static List<CatalogFood> biasByPreferred(List<CatalogFood> results, Set<String> preferred) {
        if (preferred.isEmpty()) {
            return results;
        }
        return results.stream()
            .sorted(Comparator.comparingInt(f -> preferred.contains(f.foodId()) ? 0 : 1))
            .toList();
    }

    /**
     * One-off admin backfill: recompute the {@code searchTokens} index for the
     * whole catalog after the token-search rollout. Idempotent — safe to re-run.
     */
    @PostMapping("/reindex-search")
    @AdminOnly
    public Map<String, Integer> reindexSearch() {
        return Map.of("reindexed", catalog.reindexSearch());
    }

    @GetMapping("/{foodId}")
    public FoodResponse get(@PathVariable String foodId) {
        CatalogFood food = catalog.find(foodId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return FoodResponse.from(food);
    }

    /**
     * Resolve a barcode: local catalog → Open Food Facts → cache-back. A miss
     * surfaces as {@link java.util.NoSuchElementException} from the service,
     * which we map to 404. OFF-sourced foods carry {@code source =
     * OPEN_FOOD_FACTS} so the UI can render the ADR-0006 attribution.
     */
    @GetMapping("/barcode/{code}")
    public FoodResponse byBarcode(@PathVariable String code) {
        try {
            return FoodResponse.from(catalog.getByBarcode(code));
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<FoodResponse> create(@RequestBody CreateFoodRequest body) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String userId = currentUser.get().userId();
        List<ServingSize> servings = body.servingSizes() == null
            ? List.of()
            : body.servingSizes().stream().map(ServingSizeDto::toServingSize).toList();
        int defaultIndex = body.defaultServingIndex() != null ? body.defaultServingIndex() : 0;
        // Client-minted food id + idempotent replay (D7): a durable client retry
        // (a label / meal-item confirm replayed from a background op worker) reuses
        // the same catalog food id instead of creating a duplicate food.
        String foodId = syncWrite.resolveId(body.id());
        FoodResponse response = syncWrite.idempotentCreate(
            "foods:create",
            userId,
            () -> {
                CatalogFood food = catalog.create(
                    userId,
                    body.name(),
                    body.brand(),
                    body.barcode(),
                    body.category(),
                    body.macrosPer100g() != null ? body.macrosPer100g().toMacros() : null,
                    servings,
                    defaultIndex,
                    FoodSource.USER,
                    body.referencePhotoRef(),
                    foodId
                );
                return new SyncWriteContext.Created<>(food.foodId(), FoodResponse.from(food));
            },
            id -> catalog.find(id).map(FoodResponse::from)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{foodId}/confirm")
    public FoodResponse confirm(@PathVariable String foodId) {
        String userId = currentUser.get().userId();
        CatalogFood food = catalog.confirm(foodId, userId);
        return FoodResponse.from(food);
    }

    /**
     * Delete (soft-delete / archive) a catalog food so it stops appearing in
     * search everywhere — the way to prune duplicate entries. The document is
     * kept, so previously-logged entries (which froze the food's macros) are
     * unaffected and the delete is reversible. 204 on success; 404 if unknown.
     */
    @DeleteMapping("/{foodId}")
    public ResponseEntity<Void> delete(@PathVariable String foodId) {
        String userId = currentUser.get().userId();
        try {
            catalog.archive(foodId);
            syncNotifier.changed(userId, syncWrite.originDeviceId(), "foodCatalog");
            return ResponseEntity.noContent().build();
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * One-off admin cleanup: archive duplicate catalog foods (keeps the best of
     * each name/brand/macro group). Idempotent — re-running finds nothing new.
     */
    @PostMapping("/dedupe")
    @AdminOnly
    public Map<String, Integer> dedupe() {
        return Map.of("archived", catalog.dedupe());
    }

    /**
     * Force (re)generation of a food's studio image. Generation runs async, so
     * this returns 202 Accepted with the food's current state (image status
     * will be {@code PENDING} when the image pipeline is live). A missing food
     * surfaces as {@link java.util.NoSuchElementException} → 404.
     */
    @PostMapping("/{foodId}/image/regenerate")
    public ResponseEntity<FoodResponse> regenerateImage(@PathVariable String foodId) {
        try {
            CatalogFood food = catalog.regenerateImage(foodId);
            return ResponseEntity.accepted().body(FoodResponse.from(food));
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public record CreateFoodRequest(
        String name,
        String brand,
        String barcode,
        String category,
        MacrosDto macrosPer100g,
        List<ServingSizeDto> servingSizes,
        Integer defaultServingIndex,
        /**
         * Optional reference to the user's meal-capture photo (the {@code photoRef}
         * from a capture proposal). When present, the studio image is generated
         * using that photo as a visual reference; otherwise from the name.
         */
        String referencePhotoRef,
        /** Optional client-minted food UUID for idempotent replay; null ⇒ server-generated. */
        String id
    ) {}
}
