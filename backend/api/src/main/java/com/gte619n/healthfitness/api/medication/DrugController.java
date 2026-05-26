package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.core.medication.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the shared drug catalog.
 * Endpoints: /api/drugs
 */
@RestController
@RequestMapping("/api/drugs")
public class DrugController {

    private final DrugRepository drugs;

    public DrugController(DrugRepository drugs) {
        this.drugs = drugs;
    }

    /**
     * List all drugs or search by query.
     */
    @GetMapping
    public List<DrugResponse> list(@RequestParam(required = false) String q) {
        List<Drug> results = (q != null && !q.isBlank())
            ? drugs.search(q)
            : drugs.findAll();
        return results.stream().map(DrugResponse::from).toList();
    }

    /**
     * Get a single drug by ID.
     */
    @GetMapping("/{drugId}")
    public ResponseEntity<DrugResponse> get(@PathVariable String drugId) {
        return drugs.findById(drugId)
            .map(d -> ResponseEntity.ok(DrugResponse.from(d)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new drug in the catalog.
     * Note: Image generation is triggered separately.
     */
    @PostMapping
    public ResponseEntity<DrugResponse> create(@RequestBody CreateDrugRequest body) {
        validateCreateRequest(body);

        // Check if drug already exists
        if (drugs.findByNameIgnoreCase(body.name()).isPresent()) {
            throw new IllegalArgumentException("Drug already exists: " + body.name());
        }

        String drugId = UUID.randomUUID().toString();
        String fallbackImage = getFallbackImage(body.form());

        Drug drug = new Drug(
            drugId,
            body.name(),
            body.aliases() != null ? body.aliases() : List.of(),
            body.category(),
            body.form(),
            body.defaultUnit() != null ? body.defaultUnit() : "mg",
            body.commonDoses() != null ? body.commonDoses() : List.of(),
            null,   // imageUrl - will be set by image generation
            fallbackImage,
            body.suggestedMarkers() != null ? body.suggestedMarkers() : List.of(),
            body.description(),
            Instant.now(),
            Instant.now(),
            null   // aliasOfDrugId
        );

        drugs.save(drug);
        return ResponseEntity.status(201).body(DrugResponse.from(drug));
    }

    /**
     * Update a drug in the catalog.
     */
    @PutMapping("/{drugId}")
    public ResponseEntity<DrugResponse> update(
        @PathVariable String drugId,
        @RequestBody UpdateDrugRequest body
    ) {
        Drug existing = drugs.findById(drugId)
            .orElseThrow(() -> new IllegalArgumentException("Drug not found"));

        Drug updated = new Drug(
            drugId,
            body.name() != null ? body.name() : existing.name(),
            body.aliases() != null ? body.aliases() : existing.aliases(),
            body.category() != null ? body.category() : existing.category(),
            body.form() != null ? body.form() : existing.form(),
            body.defaultUnit() != null ? body.defaultUnit() : existing.defaultUnit(),
            body.commonDoses() != null ? body.commonDoses() : existing.commonDoses(),
            body.imageUrl() != null ? body.imageUrl() : existing.imageUrl(),
            existing.imageFallback(),
            body.suggestedMarkers() != null ? body.suggestedMarkers() : existing.suggestedMarkers(),
            body.description() != null ? body.description() : existing.description(),
            existing.createdAt(),
            Instant.now(),
            existing.aliasOfDrugId()
        );

        drugs.save(updated);
        return ResponseEntity.ok(DrugResponse.from(updated));
    }

    private void validateCreateRequest(CreateDrugRequest body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (body.category() == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (body.form() == null) {
            throw new IllegalArgumentException("form is required");
        }
    }

    private String getFallbackImage(DrugForm form) {
        return switch (form) {
            case INJECTABLE_VIAL -> "/fallbacks/injectable-vial.png";
            case TABLET -> "/fallbacks/tablet.png";
            case CAPSULE -> "/fallbacks/capsule.png";
            case SOFTGEL -> "/fallbacks/softgel.png";
            case CREAM -> "/fallbacks/cream.png";
            case PATCH -> "/fallbacks/patch.png";
            case LIQUID -> "/fallbacks/liquid.png";
            case POWDER -> "/fallbacks/powder.png";
        };
    }

    // Request DTOs

    public record CreateDrugRequest(
        String name,
        List<String> aliases,
        DrugCategory category,
        DrugForm form,
        String defaultUnit,
        List<String> commonDoses,
        List<String> suggestedMarkers,
        String description
    ) {}

    public record UpdateDrugRequest(
        String name,
        List<String> aliases,
        DrugCategory category,
        DrugForm form,
        String defaultUnit,
        List<String> commonDoses,
        String imageUrl,
        List<String> suggestedMarkers,
        String description
    ) {}
}
