package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages the globally shared food catalog: name search, lookup, manual
 * creation, and the distinct-user confirmation flow that promotes a food from
 * {@code UNVERIFIED} to {@code VERIFIED}.
 *
 * <p>Kept spring-web-free to honour core's layering rule: missing-food cases
 * surface as {@link NoSuchElementException}, which controllers translate to a
 * 404 {@code ResponseStatusException}.
 */
@Service
public class FoodCatalogService {

    private static final int SEARCH_LIMIT = 25;

    private final FoodCatalogRepository repository;
    private final int verifyThreshold;
    private final ObjectProvider<BarcodeLookup> barcodeLookup;
    private final ObjectProvider<FoodImageService> foodImages;

    public FoodCatalogService(
        FoodCatalogRepository repository,
        @Value("${app.nutrition.verify-threshold:1}") int verifyThreshold,
        ObjectProvider<BarcodeLookup> barcodeLookup,
        ObjectProvider<FoodImageService> foodImages
    ) {
        this.repository = repository;
        this.verifyThreshold = verifyThreshold;
        this.barcodeLookup = barcodeLookup;
        this.foodImages = foodImages;
    }

    public List<CatalogFood> search(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        List<String> words = FoodSearchTokens.queryWords(q);
        // Token search (any-word, order-insensitive, brand-aware) is primary;
        // the legacy name-prefix query is unioned in so foods written before
        // searchTokens existed (i.e. not yet backfilled) still surface.
        LinkedHashMap<String, CatalogFood> byId = new LinkedHashMap<>();
        for (CatalogFood f : repository.searchByTokens(words, SEARCH_LIMIT)) {
            byId.putIfAbsent(f.foodId(), f);
        }
        for (CatalogFood f : repository.searchByNamePrefix(q.toLowerCase(), SEARCH_LIMIT)) {
            byId.putIfAbsent(f.foodId(), f);
        }
        String ql = q.toLowerCase().trim();
        return byId.values().stream()
            // IMPL-DRINK-01 (IL-13): alcoholic drinks live only on the Drink card,
            // never in the normal food/add-food search. Archived foods are hidden too.
            .filter(f -> !f.isDrink() && !f.isArchived())
            .sorted(Comparator
                .comparingInt((CatalogFood f) -> rank(f, ql)).reversed()
                .thenComparing(f -> f.nameLower() == null ? "" : f.nameLower()))
            .limit(SEARCH_LIMIT)
            .toList();
    }

    /**
     * Relevance buckets, highest first: exact name, name-prefix, name-contains,
     * then token/brand-only matches. Ties break alphabetically (caller-applied).
     */
    private static int rank(CatalogFood f, String ql) {
        String name = f.nameLower() == null ? "" : f.nameLower();
        if (name.equals(ql)) {
            return 3;
        }
        if (name.startsWith(ql)) {
            return 2;
        }
        if (name.contains(ql)) {
            return 1;
        }
        return 0;
    }

    /** One-off backfill: recompute the search-token index across the catalog. */
    public int reindexSearch() {
        return repository.reindexSearchTokens();
    }

    public Optional<CatalogFood> find(String foodId) {
        return repository.findById(foodId);
    }

    /** Look up a food or raise {@link NoSuchElementException} when absent. */
    public CatalogFood get(String foodId) {
        return repository.findById(foodId)
            .orElseThrow(() -> new NoSuchElementException("food not found: " + foodId));
    }

    /**
     * Resolve a barcode following the spec's lookup order: local catalog →
     * Open Food Facts API (cached back into the catalog) → miss.
     *
     * <p>On a local hit the stored food is returned as-is. On a local miss the
     * (optional) {@link BarcodeLookup} is consulted; a hit is persisted with a
     * deterministic {@code "off-" + barcode} id (so repeated scans are
     * idempotent and the <em>second</em> scan is free) tagged
     * {@code source = OPEN_FOOD_FACTS}, then returned. A miss — or no
     * {@code BarcodeLookup} bean available (core-only test context) — raises
     * {@link NoSuchElementException}, which controllers map to 404.
     */
    public CatalogFood getByBarcode(String code) {
        if (code == null || code.isBlank()) {
            throw new NoSuchElementException("barcode is required");
        }
        Optional<CatalogFood> local = repository.findByBarcode(code);
        if (local.isPresent()) {
            return local.get();
        }
        BarcodeLookup lookup = barcodeLookup.getIfAvailable();
        if (lookup != null) {
            Optional<CatalogFood> remote = lookup.lookupByBarcode(code);
            if (remote.isPresent()) {
                CatalogFood cached = withId(remote.get(), "off-" + code);
                repository.save(cached);
                return cached;
            }
        }
        throw new NoSuchElementException("food not found for barcode: " + code);
    }

    private static CatalogFood withId(CatalogFood food, String foodId) {
        return new CatalogFood(
            foodId,
            food.name(),
            food.nameLower(),
            food.brand(),
            food.barcode(),
            food.category(),
            food.macrosPer100g(),
            food.servingSizes(),
            food.defaultServingIndex(),
            food.source(),
            food.sourceRef(),
            food.status(),
            food.confirmationCount(),
            food.verifiedAt(),
            food.imageUrl(),
            food.imageStatus(),
            food.createdBy(),
            food.createdAt(),
            food.updatedAt(),
            food.alcohol(),
            food.archivedAt()
        );
    }

    /**
     * Find an existing packaged-product food by exact name (and brand when
     * given), so repeat captures of the same product reuse one catalog food —
     * and its already-generated studio image — instead of minting duplicates.
     */
    public Optional<CatalogFood> findProduct(String name, String brand) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return repository.searchByNamePrefix(name.toLowerCase(), SEARCH_LIMIT).stream()
            .filter(f -> "product".equalsIgnoreCase(f.category()))
            .filter(f -> f.name() != null && f.name().equalsIgnoreCase(name))
            .filter(f -> brand == null
                || (f.brand() != null && f.brand().equalsIgnoreCase(brand)))
            .findFirst();
    }

    /** Create a manual / AI-derived catalog food. Starts {@code UNVERIFIED}. */
    public CatalogFood create(
        String createdByUserId,
        String name,
        String brand,
        String barcode,
        String category,
        Macros macrosPer100g,
        List<ServingSize> servingSizes,
        int defaultServingIndex,
        FoodSource source
    ) {
        return create(createdByUserId, name, brand, barcode, category,
            macrosPer100g, servingSizes, defaultServingIndex, source, null);
    }

    /**
     * Create a catalog food and enqueue async studio-image generation (IMPL-13
     * M4). When {@code referencePhotoRef} is present (the user's meal-capture
     * photo) it is fed to the generator as a visual reference; otherwise the
     * image is generated from the food name/category. Generation is fire-and-
     * forget — the returned food still reports its in-flight image status.
     */
    public CatalogFood create(
        String createdByUserId,
        String name,
        String brand,
        String barcode,
        String category,
        Macros macrosPer100g,
        List<ServingSize> servingSizes,
        int defaultServingIndex,
        FoodSource source,
        String referencePhotoRef
    ) {
        return create(createdByUserId, name, brand, barcode, category, macrosPer100g,
            servingSizes, defaultServingIndex, source, referencePhotoRef, null);
    }

    /**
     * As above, but with a caller-supplied {@code foodId} so a create can be made
     * idempotent — a durable client retry (e.g. a label/meal-item confirm replayed
     * from a background worker) reuses the same food id instead of minting a
     * duplicate catalog food. A null/blank id falls back to a server-generated
     * UUID — the previous behaviour.
     */
    public CatalogFood create(
        String createdByUserId,
        String name,
        String brand,
        String barcode,
        String category,
        Macros macrosPer100g,
        List<ServingSize> servingSizes,
        int defaultServingIndex,
        FoodSource source,
        String referencePhotoRef,
        String foodId
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        CatalogFood food = new CatalogFood(
            (foodId != null && !foodId.isBlank()) ? foodId : UUID.randomUUID().toString(),
            name,
            name.toLowerCase(),
            brand,
            barcode,
            category,
            macrosPer100g != null ? macrosPer100g.withDerivedCalories() : null,
            servingSizes != null ? servingSizes : List.of(),
            defaultServingIndex,
            source != null ? source : FoodSource.USER,
            null,
            FoodStatus.UNVERIFIED,
            0,
            null,
            null,
            FoodImageStatus.NONE,
            createdByUserId,
            null,
            null,
            null,
            null
        );
        repository.save(food);
        FoodImageService images = foodImages.getIfAvailable();
        if (images != null) {
            images.enqueueGeneration(food.foodId(), referencePhotoRef);
        }
        return food;
    }

    // ----- Drinks (IMPL-DRINK-01) --------------------------------------

    /**
     * Create an alcoholic-drink catalog food (category {@code "drink"}) and enqueue
     * its beverage-style studio image. Unlike {@link #create}, calories are NOT
     * re-derived from macros — they are computed explicitly to include alcohol
     * (7 kcal/g), which {@link Macros#withDerivedCalories()} would otherwise drop
     * (IL-3). The drink is stored as per-100(ml≈g) macros with a single serving
     * equal to {@code servingVolumeMl}, so the log-time quantity multiplier reuses
     * the standard entry scaling (IL-4).
     *
     * @param macroContribution the mixer/sugar macros for ONE serving (protein/
     *     carbs/fat/fiber/sugar), excluding alcohol calories; may be null/zero for
     *     a neat spirit
     */
    public CatalogFood createDrink(
        String createdByUserId,
        String name,
        Double abvPercent,
        Double servingVolumeMl,
        Macros macroContribution,
        String servingLabel,
        String foodId
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (abvPercent == null || abvPercent <= 0 || servingVolumeMl == null || servingVolumeMl <= 0) {
            throw new IllegalArgumentException("a drink requires positive ABV% and serving volume");
        }
        AlcoholInfo alcohol = DrinkMath.describe(abvPercent, servingVolumeMl);
        double grams = alcohol.alcoholGrams() != null ? alcohol.alcoholGrams() : 0.0;
        double servingCalories = DrinkMath.servingCalories(macroContribution, grams);
        Macros serving = new Macros(
            servingCalories,
            macroContribution != null ? macroContribution.proteinGrams() : null,
            macroContribution != null ? macroContribution.carbsGrams() : null,
            macroContribution != null ? macroContribution.fatGrams() : null,
            macroContribution != null ? macroContribution.fiberGrams() : null,
            macroContribution != null ? macroContribution.sugarGrams() : null);
        // Normalize to per-100 units so entry scaling (grams×qty/100) reproduces the
        // serving at quantity 1 and multiplies it at the long-press multiplier.
        Macros per100 = serving.scale(100.0 / servingVolumeMl);
        String label = (servingLabel != null && !servingLabel.isBlank())
            ? servingLabel
            : defaultDrinkLabel(servingVolumeMl);
        CatalogFood food = new CatalogFood(
            (foodId != null && !foodId.isBlank()) ? foodId : UUID.randomUUID().toString(),
            name,
            name.toLowerCase(),
            null,
            null,
            "drink",
            per100,
            List.of(new ServingSize(label, servingVolumeMl)),
            0,
            FoodSource.GEMINI_DESCRIPTION,
            null,
            FoodStatus.UNVERIFIED,
            0,
            null,
            null,
            FoodImageStatus.NONE,
            createdByUserId,
            null,
            null,
            alcohol,
            null
        );
        repository.save(food);
        FoodImageService images = foodImages.getIfAvailable();
        if (images != null) {
            images.enqueueGeneration(food.foodId(), null);
        }
        return food;
    }

    private static String defaultDrinkLabel(double ml) {
        long rounded = Math.round(ml);
        return "1 serving (" + rounded + " ml)";
    }

    /**
     * Edit a drink's fields and re-derive its alcohol/calorie maths. Only the
     * drink-relevant fields are updatable; image and confirmation state are
     * preserved. Throws {@link NoSuchElementException} for an unknown id.
     */
    public CatalogFood updateDrink(
        String foodId,
        String name,
        Double abvPercent,
        Double servingVolumeMl,
        Macros macroContribution,
        String servingLabel
    ) {
        CatalogFood existing = get(foodId);
        if (abvPercent == null || abvPercent <= 0 || servingVolumeMl == null || servingVolumeMl <= 0) {
            throw new IllegalArgumentException("a drink requires positive ABV% and serving volume");
        }
        String newName = (name != null && !name.isBlank()) ? name : existing.name();
        AlcoholInfo alcohol = DrinkMath.describe(abvPercent, servingVolumeMl);
        double grams = alcohol.alcoholGrams() != null ? alcohol.alcoholGrams() : 0.0;
        double servingCalories = DrinkMath.servingCalories(macroContribution, grams);
        Macros serving = new Macros(
            servingCalories,
            macroContribution != null ? macroContribution.proteinGrams() : null,
            macroContribution != null ? macroContribution.carbsGrams() : null,
            macroContribution != null ? macroContribution.fatGrams() : null,
            macroContribution != null ? macroContribution.fiberGrams() : null,
            macroContribution != null ? macroContribution.sugarGrams() : null);
        Macros per100 = serving.scale(100.0 / servingVolumeMl);
        String label = (servingLabel != null && !servingLabel.isBlank())
            ? servingLabel
            : defaultDrinkLabel(servingVolumeMl);
        CatalogFood updated = new CatalogFood(
            existing.foodId(),
            newName,
            newName.toLowerCase(),
            existing.brand(),
            existing.barcode(),
            "drink",
            per100,
            List.of(new ServingSize(label, servingVolumeMl)),
            0,
            existing.source(),
            existing.sourceRef(),
            existing.status(),
            existing.confirmationCount(),
            existing.verifiedAt(),
            existing.imageUrl(),
            existing.imageStatus(),
            existing.createdBy(),
            existing.createdAt(),
            null,
            alcohol,
            existing.archivedAt()
        );
        repository.save(updated);
        return updated;
    }

    /** My non-archived drinks (category {@code "drink"}, created by me), newest first. */
    public List<CatalogFood> listMyDrinks(String userId) {
        return repository.findByCreatedByAndCategory(userId, "drink").stream()
            .filter(f -> !f.isArchived())
            .sorted(Comparator.comparing(
                CatalogFood::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /** Soft-delete (archive) a drink: hide it from listings; keep the document. */
    public CatalogFood archiveDrink(String foodId) {
        CatalogFood food = get(foodId);
        CatalogFood updated = new CatalogFood(
            food.foodId(), food.name(), food.nameLower(), food.brand(), food.barcode(),
            food.category(), food.macrosPer100g(), food.servingSizes(), food.defaultServingIndex(),
            food.source(), food.sourceRef(), food.status(), food.confirmationCount(),
            food.verifiedAt(), food.imageUrl(), food.imageStatus(), food.createdBy(),
            food.createdAt(), null, food.alcohol(), Instant.now());
        repository.save(updated);
        return updated;
    }

    /**
     * Force (re)generation of a food's studio image, asynchronously (IMPL-13 M4
     * regenerate endpoint). Looks the food up so callers get a 404 for unknown
     * ids, then enqueues — a no-op when the image pipeline is unavailable.
     */
    public CatalogFood regenerateImage(String foodId) {
        CatalogFood food = get(foodId);
        FoodImageService images = foodImages.getIfAvailable();
        if (images != null) {
            images.enqueueGeneration(foodId, null);
            return get(foodId);
        }
        return food;
    }

    /**
     * Self-heal orphaned studio-image generation (see
     * {@link FoodImageService#sweepStalePending()}): re-enqueue catalog foods
     * stuck at {@code PENDING} past the stale window. A no-op when the image
     * pipeline is unavailable. Returns the count re-enqueued.
     */
    public int sweepStalePendingImages() {
        FoodImageService images = foodImages.getIfAvailable();
        return images != null ? images.sweepStalePending() : 0;
    }

    /**
     * Heal the images of specific catalog foods (a day's single-food entries),
     * covering NONE/FAILED as well as stale PENDING — see
     * {@link FoodImageService#healImages(java.util.Collection)}. A no-op when the
     * image pipeline is unavailable. Returns the count re-enqueued.
     */
    public int healReferencedImages(java.util.Collection<CatalogFood> foods) {
        FoodImageService images = foodImages.getIfAvailable();
        return images != null ? images.healImages(foods) : 0;
    }

    /**
     * Record one distinct user's confirmation. Recomputes the denormalized
     * count and promotes the food to {@code VERIFIED} once it reaches the
     * configured threshold.
     */
    public CatalogFood confirm(String foodId, String userId) {
        CatalogFood food = get(foodId);
        repository.saveConfirmation(foodId, userId);
        int count = repository.countConfirmations(foodId);

        FoodStatus status = food.status();
        Instant verifiedAt = food.verifiedAt();
        if (count >= verifyThreshold && status != FoodStatus.VERIFIED) {
            status = FoodStatus.VERIFIED;
            verifiedAt = Instant.now();
        }
        CatalogFood updated = new CatalogFood(
            food.foodId(),
            food.name(),
            food.nameLower(),
            food.brand(),
            food.barcode(),
            food.category(),
            food.macrosPer100g(),
            food.servingSizes(),
            food.defaultServingIndex(),
            food.source(),
            food.sourceRef(),
            status,
            count,
            verifiedAt,
            food.imageUrl(),
            food.imageStatus(),
            food.createdBy(),
            food.createdAt(),
            null,
            food.alcohol(),
            food.archivedAt()
        );
        repository.save(updated);
        return updated;
    }
}
