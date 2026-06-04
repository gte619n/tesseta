package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogService;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.ServingSize;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Global, authenticated (not user-scoped) food catalog API. */
@RestController
@RequestMapping("/api/foods")
public class FoodController {

    private final CurrentUserProvider currentUser;
    private final FoodCatalogService catalog;

    public FoodController(CurrentUserProvider currentUser, FoodCatalogService catalog) {
        this.currentUser = currentUser;
        this.catalog = catalog;
    }

    @GetMapping("/search")
    public List<FoodResponse> search(@RequestParam(value = "q", required = false) String q) {
        return catalog.search(q).stream().map(FoodResponse::from).toList();
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
            body.referencePhotoRef()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(FoodResponse.from(food));
    }

    @PostMapping("/{foodId}/confirm")
    public FoodResponse confirm(@PathVariable String foodId) {
        String userId = currentUser.get().userId();
        CatalogFood food = catalog.confirm(foodId, userId);
        return FoodResponse.from(food);
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
        String referencePhotoRef
    ) {}
}
