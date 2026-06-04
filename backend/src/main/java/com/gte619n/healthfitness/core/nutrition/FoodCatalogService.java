package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
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
        return repository.searchByNamePrefix(q.toLowerCase(), SEARCH_LIMIT);
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
            food.updatedAt()
        );
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
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        CatalogFood food = new CatalogFood(
            UUID.randomUUID().toString(),
            name,
            name.toLowerCase(),
            brand,
            barcode,
            category,
            macrosPer100g,
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
            null
        );
        repository.save(food);
        FoodImageService images = foodImages.getIfAvailable();
        if (images != null) {
            images.enqueueGeneration(food.foodId(), referencePhotoRef);
        }
        return food;
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
            null
        );
        repository.save(updated);
        return updated;
    }
}
