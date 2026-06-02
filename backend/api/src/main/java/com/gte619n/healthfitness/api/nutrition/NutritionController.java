package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.api.sync.WriteResult;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.CompositeIngredient;
import com.gte619n.healthfitness.core.nutrition.EntrySource;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogService;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodEntryImageService;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetService;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.core.nutrition.NutritionService;
import com.gte619n.healthfitness.core.nutrition.ServingSize;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/nutrition")
public class NutritionController {

    private final CurrentUserProvider currentUser;
    private final NutritionService nutrition;
    private final MacroTargetService targets;
    private final FoodCatalogService foodCatalog;
    private final FoodEntryImageService foodEntryImages;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public NutritionController(
        CurrentUserProvider currentUser,
        NutritionService nutrition,
        MacroTargetService targets,
        FoodCatalogService foodCatalog,
        FoodEntryImageService foodEntryImages,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.nutrition = nutrition;
        this.targets = targets;
        this.foodCatalog = foodCatalog;
        this.foodEntryImages = foodEntryImages;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    // ----- Legacy day-total quick entry --------------------------------

    @PostMapping
    public ResponseEntity<LogResponse> upsert(@RequestBody LogRequest body) {
        if (body == null || body.date() == null) {
            throw new IllegalArgumentException("date is required");
        }
        String userId = currentUser.get().userId();
        NutritionDailyLog log = nutrition.logDay(
            userId,
            body.date(),
            body.proteinGrams(),
            body.carbsGrams(),
            body.fatGrams(),
            body.fiberGrams(),
            body.sugarGrams(),
            body.caloriesKcal());
        return ResponseEntity.status(201).body(LogResponse.from(log));
    }

    @GetMapping
    public List<LogResponse> list(
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        String userId = currentUser.get().userId();
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        return nutrition.findRange(userId, start, end).stream()
            .map(LogResponse::from)
            .toList();
    }

    @GetMapping("/today")
    public ResponseEntity<LogResponse> today() {
        String userId = currentUser.get().userId();
        return nutrition.findByDate(userId, LocalDate.now())
            .map(log -> ResponseEntity.ok(LogResponse.from(log)))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // ----- Macro target -------------------------------------------------

    @GetMapping("/target")
    public ResponseEntity<MacrosDto> getTarget() {
        String userId = currentUser.get().userId();
        return targets.getActive(userId)
            .map(t -> ResponseEntity.ok(MacrosDto.from(t.macros())))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/target")
    public WriteResult<MacrosDto> setTarget(@RequestBody MacrosDto body) {
        if (body == null) {
            throw new IllegalArgumentException("target macros are required");
        }
        String userId = currentUser.get().userId();
        // PUT is set-semantics (inherently idempotent), so no Idempotency-Key /
        // client-id is needed; the write still fans out (origin suppressed) and
        // carries an authoritative lastUpdate (#11, D18).
        java.time.Instant writtenAt = java.time.Instant.now();
        MacroTarget target = targets.setTarget(userId, body.toMacros());
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "nutritionTargets");
        return WriteResult.of(
            MacrosDto.from(target.macros()),
            target.updatedAt() != null ? target.updatedAt() : writtenAt);
    }

    // ----- Day view + entries ------------------------------------------

    @GetMapping("/{date}")
    public DayResponse day(@PathVariable LocalDate date) {
        String userId = currentUser.get().userId();
        List<FoodEntry> entries = nutrition.listEntries(userId, date);

        Macros totals = nutrition.findByDate(userId, date)
            .map(NutritionController::macrosOf)
            .orElseGet(Macros::zero);

        MacrosDto target = targets.getActive(userId)
            .map(t -> MacrosDto.from(t.macros()))
            .orElse(null);

        // Join in each entry's catalog food once, so we can surface the
        // generated studio image without an N+1 lookup per meal group.
        Map<String, CatalogFood> foods = loadFoods(entries);

        List<MealGroup> meals = new ArrayList<>();
        for (MealType meal : MealType.values()) {
            List<EntryResponse> mealEntries = new ArrayList<>();
            Macros subtotal = Macros.zero();
            for (FoodEntry e : entries) {
                if (e.meal() == meal) {
                    mealEntries.add(toResponse(e, foods));
                    subtotal = subtotal.plus(e.macros());
                }
            }
            meals.add(new MealGroup(meal, MacrosDto.from(subtotal), mealEntries));
        }

        return new DayResponse(date, MacrosDto.from(totals), target, meals);
    }

    @PostMapping("/{date}/entries")
    public ResponseEntity<WriteResult<EntryResponse>> addEntry(
        @PathVariable LocalDate date,
        @RequestBody AddEntryRequest body
    ) {
        if (body == null || body.meal() == null) {
            throw new IllegalArgumentException("meal is required");
        }
        String userId = currentUser.get().userId();
        // Client-minted entry id + idempotent replay (IMPL-AND-20 D7). The
        // entry's identity is (userId, date, entryId); the replay loader keys on
        // the same date.
        String entryId = syncWrite.resolveId(body.id());

        WriteResult<EntryResponse> response = syncWrite.idempotentCreate(
            "nutritionEntries:create",
            userId,
            () -> {
                java.time.Instant writtenAt = java.time.Instant.now();
                FoodEntry entry = nutrition.addEntry(
                    userId,
                    date,
                    body.meal(),
                    body.foodId(),
                    body.foodName(),
                    body.servingLabel(),
                    body.servingGrams(),
                    body.quantity(),
                    body.macros() != null ? body.macros().toMacros() : null,
                    body.source(),
                    entryId);
                // Fan-out collection name matches the delta feed's emitted
                // "nutritionDays/entries" exactly (IMPL-AND-20 #34).
                syncNotifier.changed(userId, syncWrite.originDeviceId(), "nutritionDays/entries");
                return new SyncWriteContext.Created<>(
                    entry.entryId(), WriteResult.of(toResponse(entry), writtenAt));
            },
            id -> nutrition.findEntry(userId, date, id)
                .map(e -> WriteResult.of(toResponse(e),
                    e.updatedAt() != null ? e.updatedAt() : java.time.Instant.now()))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{date}/entries/{entryId}")
    public EntryResponse updateEntry(
        @PathVariable LocalDate date,
        @PathVariable String entryId,
        @RequestBody UpdateEntryRequest body
    ) {
        String userId = currentUser.get().userId();
        FoodEntry entry = nutrition.updateEntry(
            userId,
            date,
            entryId,
            body != null ? body.meal() : null,
            body != null ? body.foodName() : null,
            body != null ? body.servingLabel() : null,
            body != null ? body.servingGrams() : null,
            body != null ? body.quantity() : null,
            body != null && body.macros() != null ? body.macros().toMacros() : null);
        // PATCH edits are idempotent set-semantics, so no Idempotency-Key / id is
        // needed; the write still fans out (origin suppressed) under the delta
        // feed's "nutritionDays/entries" name (IMPL-AND-20 #8/#34).
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "nutritionDays/entries");
        return toResponse(entry);
    }

    @DeleteMapping("/{date}/entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(
        @PathVariable LocalDate date,
        @PathVariable String entryId
    ) {
        String userId = currentUser.get().userId();
        nutrition.deleteEntry(userId, date, entryId);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "nutritionDays/entries");
        return ResponseEntity.noContent().build();
    }

    // ----- Composite meal (photo-logged) -------------------------------

    /**
     * Log a photographed meal as one composite entry. Each ingredient becomes a
     * catalog food (tagged "ingredient" so its image generates as the raw item),
     * then the entry is created with those ingredients and a finished-meal image
     * is generated asynchronously from the meal name + the user's capture photo.
     */
    @PostMapping("/{date}/composite-meal")
    public ResponseEntity<WriteResult<EntryResponse>> addCompositeMeal(
        @PathVariable LocalDate date,
        @RequestBody CompositeMealRequest body
    ) {
        if (body == null || body.meal() == null) {
            throw new IllegalArgumentException("meal is required");
        }
        if (body.mealName() == null || body.mealName().isBlank()) {
            throw new IllegalArgumentException("mealName is required");
        }
        if (body.ingredients() == null || body.ingredients().isEmpty()) {
            throw new IllegalArgumentException("at least one ingredient is required");
        }
        String userId = currentUser.get().userId();
        // Client-minted entry id + idempotent replay (IMPL-AND-20 D7). The whole
        // create — ingredient catalog-food creation (with AI image gen) AND the
        // entry write AND the finished-meal image enqueue — runs at most once per
        // key, so a replay never re-triggers AI generation or duplicates foods.
        String entryId = syncWrite.resolveId(body.id());

        WriteResult<EntryResponse> response = syncWrite.idempotentCreate(
            "nutritionCompositeMeal:create",
            userId,
            () -> {
                java.time.Instant writtenAt = java.time.Instant.now();
                List<CompositeIngredient> ingredients = new ArrayList<>();
                for (CompositeIngredientDto dto : body.ingredients()) {
                    Macros per100g = dto.macrosPer100g() != null ? dto.macrosPer100g().toMacros() : null;
                    double grams = dto.servingGrams() != null ? dto.servingGrams() : 0.0;
                    double qty = dto.quantity() != null ? dto.quantity() : 1.0;
                    Macros portion = dto.macros() != null
                        ? dto.macros().toMacros()
                        : (per100g != null ? per100g.scale((grams * qty) / 100.0) : Macros.zero());
                    // Catalog food for the raw-ingredient image (fire-and-forget gen).
                    CatalogFood food = foodCatalog.create(
                        userId,
                        dto.name(),
                        null,
                        null,
                        "ingredient",
                        per100g,
                        List.of(new ServingSize(
                            dto.servingLabel() != null ? dto.servingLabel() : "100 g",
                            grams > 0 ? grams : 100.0)),
                        0,
                        FoodSource.GEMINI_PHOTO,
                        null);
                    ingredients.add(new CompositeIngredient(
                        dto.name(), food.foodId(), per100g, grams, dto.servingLabel(), qty, portion));
                }

                FoodEntry entry = nutrition.addCompositeMeal(
                    userId, date, body.meal(), body.mealName(), ingredients,
                    EntrySource.PHOTO, entryId);
                foodEntryImages.enqueueGeneration(
                    userId, date, entry.entryId(), body.mealName(), body.referencePhotoRef());
                syncNotifier.changed(userId, syncWrite.originDeviceId(), "nutritionDays/entries");
                return new SyncWriteContext.Created<>(
                    entry.entryId(), WriteResult.of(toResponse(entry), writtenAt));
            },
            id -> nutrition.findEntry(userId, date, id)
                .map(e -> WriteResult.of(toResponse(e),
                    e.updatedAt() != null ? e.updatedAt() : java.time.Instant.now()))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Re-portion one ingredient of a composite meal and recompute totals. */
    @PatchMapping("/{date}/entries/{entryId}/ingredients/{index}")
    public EntryResponse updateIngredient(
        @PathVariable LocalDate date,
        @PathVariable String entryId,
        @PathVariable int index,
        @RequestBody UpdateIngredientRequest body
    ) {
        String userId = currentUser.get().userId();
        FoodEntry entry = nutrition.updateIngredient(
            userId,
            date,
            entryId,
            index,
            body != null ? body.servingGrams() : null,
            body != null ? body.servingLabel() : null,
            body != null ? body.quantity() : null);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "nutritionDays/entries");
        return toResponse(entry);
    }

    /**
     * Distinct catalog foods backing a day's entries, keyed by foodId — both the
     * entry's own food and every composite-meal ingredient's food, so their
     * generated images can be joined without an N+1 lookup.
     */
    private Map<String, CatalogFood> loadFoods(List<FoodEntry> entries) {
        Map<String, CatalogFood> foods = new HashMap<>();
        for (FoodEntry e : entries) {
            cacheFood(foods, e.foodId());
            if (e.ingredients() != null) {
                for (CompositeIngredient ing : e.ingredients()) {
                    cacheFood(foods, ing.foodId());
                }
            }
        }
        return foods;
    }

    private void cacheFood(Map<String, CatalogFood> foods, String foodId) {
        if (foodId != null && !foods.containsKey(foodId)) {
            foodCatalog.find(foodId).ifPresent(f -> foods.put(foodId, f));
        }
    }

    /** Map an entry, pulling images from a pre-loaded food cache. */
    private static EntryResponse toResponse(FoodEntry e, Map<String, CatalogFood> foods) {
        if (e.isComposite()) {
            // Composite meal: display image is the finished-meal image stored on
            // the entry; each ingredient carries its own raw-ingredient image.
            return EntryResponse.from(
                e, e.mealImageUrl(), e.mealImageStatus(), ingredientResponses(e, foods));
        }
        CatalogFood food = e.foodId() != null ? foods.get(e.foodId()) : null;
        return EntryResponse.from(
            e,
            food != null ? food.imageUrl() : null,
            food != null ? food.imageStatus() : FoodImageStatus.NONE);
    }

    /** Map a single entry, looking its catalog foods up on demand. */
    private EntryResponse toResponse(FoodEntry e) {
        return toResponse(e, loadFoods(List.of(e)));
    }

    private static List<EntryResponse.IngredientResponse> ingredientResponses(
        FoodEntry e, Map<String, CatalogFood> foods) {
        List<EntryResponse.IngredientResponse> out = new ArrayList<>();
        for (CompositeIngredient ing : e.ingredients()) {
            CatalogFood food = ing.foodId() != null ? foods.get(ing.foodId()) : null;
            out.add(new EntryResponse.IngredientResponse(
                ing.name(),
                ing.foodId(),
                ing.servingLabel(),
                ing.servingGrams(),
                ing.quantity(),
                MacrosDto.from(ing.macros()),
                MacrosDto.from(ing.macrosPer100g()),
                food != null ? food.imageUrl() : null,
                food != null ? food.imageStatus() : FoodImageStatus.NONE));
        }
        return out;
    }

    private static Macros macrosOf(NutritionDailyLog log) {
        return new Macros(
            log.caloriesKcal(),
            log.proteinGrams(),
            log.carbsGrams(),
            log.fatGrams(),
            log.fiberGrams(),
            log.sugarGrams());
    }

    // ----- DTOs ---------------------------------------------------------

    public record LogRequest(
        LocalDate date,
        Double proteinGrams,
        Double carbsGrams,
        Double fatGrams,
        Double fiberGrams,
        Double sugarGrams,
        Double caloriesKcal
    ) {}

    public record LogResponse(
        LocalDate date,
        Double proteinGrams,
        Double carbsGrams,
        Double fatGrams,
        Double fiberGrams,
        Double sugarGrams,
        Double caloriesKcal
    ) {
        public static LogResponse from(NutritionDailyLog log) {
            return new LogResponse(
                log.date(),
                log.proteinGrams(),
                log.carbsGrams(),
                log.fatGrams(),
                log.fiberGrams(),
                log.sugarGrams(),
                log.caloriesKcal());
        }
    }

    public record DayResponse(
        LocalDate date,
        MacrosDto totals,
        MacrosDto target,
        List<MealGroup> meals
    ) {}

    public record MealGroup(
        MealType meal,
        MacrosDto subtotal,
        List<EntryResponse> entries
    ) {}

    public record AddEntryRequest(
        String id,                  // optional client-minted UUID (IMPL-AND-20 D7); null ⇒ server-generated
        MealType meal,
        String foodId,
        String foodName,
        String servingLabel,
        Double servingGrams,
        Double quantity,
        MacrosDto macros,
        com.gte619n.healthfitness.core.nutrition.EntrySource source
    ) {}

    public record UpdateEntryRequest(
        MealType meal,
        String foodName,
        String servingLabel,
        Double servingGrams,
        Double quantity,
        MacrosDto macros
    ) {}

    public record CompositeMealRequest(
        String id,                  // optional client-minted UUID (IMPL-AND-20 D7); null ⇒ server-generated
        MealType meal,
        String mealName,
        List<CompositeIngredientDto> ingredients,
        String referencePhotoRef
    ) {}

    public record CompositeIngredientDto(
        String name,
        Double servingGrams,
        String servingLabel,
        Double quantity,
        MacrosDto macrosPer100g,
        MacrosDto macros
    ) {}

    public record UpdateIngredientRequest(
        Double servingGrams,
        String servingLabel,
        Double quantity
    ) {}
}
