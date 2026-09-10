package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.DrinkService;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogService;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.integrations.nutrition.NutritionExtractionException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * IMPL-DRINK-01 drink catalog API. A "drink" is a {@link CatalogFood} with
 * {@code category = "drink"} owned by the requesting user; logging a drink still
 * goes through the normal nutrition add-entry path.
 *
 * <ul>
 *   <li>{@code POST /analyze} — AI proposal (nothing persisted); 422 on failure.</li>
 *   <li>{@code GET /} — my non-archived drinks.</li>
 *   <li>{@code POST /} — create (persists + enqueues beverage image).</li>
 *   <li>{@code PUT /{id}} — edit (re-derives alcohol maths).</li>
 *   <li>{@code POST /{id}/image/regenerate} — 202, re-enqueue image.</li>
 *   <li>{@code DELETE /{id}} — archive (soft-delete).</li>
 * </ul>
 *
 * <p>Every mutating call fans out under the {@code foodCatalog} collection so
 * other devices re-warm their local drink cache.
 */
@RestController
@RequestMapping("/api/me/drinks")
public class DrinkController {

    private final CurrentUserProvider currentUser;
    private final DrinkService drinks;
    private final FoodCatalogService catalog;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public DrinkController(
        CurrentUserProvider currentUser,
        DrinkService drinks,
        FoodCatalogService catalog,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.drinks = drinks;
        this.catalog = catalog;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    @GetMapping
    public List<FoodResponse> list() {
        String userId = currentUser.get().userId();
        return catalog.listMyDrinks(userId).stream().map(FoodResponse::from).toList();
    }

    /**
     * AI proposal for a drink name. Nothing is persisted — the client reviews and
     * edits before saving. Analyzer failure/unavailability → 422 so the web form
     * can fall back to fully-manual entry.
     */
    @PostMapping("/analyze")
    public DrinkProposalResponse analyze(@RequestBody AnalyzeDrinkRequest body) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        try {
            return DrinkProposalResponse.from(drinks.analyze(body.name()));
        } catch (IllegalStateException | NutritionExtractionException e) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "could not analyze the drink");
        }
    }

    @PostMapping
    public ResponseEntity<FoodResponse> create(@RequestBody SaveDrinkRequest body) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String userId = currentUser.get().userId();
        String foodId = syncWrite.resolveId(body.id());
        FoodResponse response = syncWrite.idempotentCreate(
            "drinks:create",
            userId,
            () -> {
                CatalogFood food;
                try {
                    food = catalog.createDrink(
                        userId,
                        body.name(),
                        body.abvPercent(),
                        body.servingVolumeMl(),
                        body.macroContribution(),
                        body.servingLabel(),
                        foodId);
                } catch (IllegalArgumentException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
                }
                syncNotifier.changed(userId, syncWrite.originDeviceId(), "foodCatalog");
                return new SyncWriteContext.Created<>(food.foodId(), FoodResponse.from(food));
            },
            id -> catalog.find(id).map(FoodResponse::from)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{foodId}")
    public FoodResponse update(@PathVariable String foodId, @RequestBody SaveDrinkRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        String userId = currentUser.get().userId();
        try {
            CatalogFood food = catalog.updateDrink(
                foodId,
                body.name(),
                body.abvPercent(),
                body.servingVolumeMl(),
                body.macroContribution(),
                body.servingLabel());
            syncNotifier.changed(userId, syncWrite.originDeviceId(), "foodCatalog");
            return FoodResponse.from(food);
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{foodId}/image/regenerate")
    public ResponseEntity<FoodResponse> regenerateImage(@PathVariable String foodId) {
        String userId = currentUser.get().userId();
        try {
            CatalogFood food = catalog.regenerateImage(foodId);
            syncNotifier.changed(userId, syncWrite.originDeviceId(), "foodCatalog");
            return ResponseEntity.accepted().body(FoodResponse.from(food));
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{foodId}")
    public ResponseEntity<Void> archive(@PathVariable String foodId) {
        String userId = currentUser.get().userId();
        try {
            catalog.archiveDrink(foodId);
            syncNotifier.changed(userId, syncWrite.originDeviceId(), "foodCatalog");
            return ResponseEntity.noContent().build();
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ----- DTOs ---------------------------------------------------------

    public record AnalyzeDrinkRequest(String name) {}

    /** Create/update request. {@code macros} is the per-serving mixer contribution. */
    public record SaveDrinkRequest(
        String id,
        String name,
        Double abvPercent,
        Double servingVolumeMl,
        String servingLabel,
        MacrosDto macros
    ) {
        Macros macroContribution() {
            return macros != null ? macros.toMacros() : null;
        }
    }

    /** The review-ready proposal returned by {@code /analyze}. */
    public record DrinkProposalResponse(
        String name,
        Double abvPercent,
        Double servingVolumeMl,
        MacrosDto servingMacros,
        FoodResponse.AlcoholDto alcohol
    ) {
        static DrinkProposalResponse from(DrinkService.DrinkProposal p) {
            return new DrinkProposalResponse(
                p.name(),
                p.abvPercent(),
                p.servingVolumeMl(),
                MacrosDto.from(p.servingMacros()),
                FoodResponse.AlcoholDto.from(p.alcohol()));
        }
    }
}
