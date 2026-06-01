package com.gte619n.healthfitness.core.medication;

import java.time.Instant;
import java.util.List;

/**
 * Shared drug catalog entry (system-owned).
 * Stored in: drugs/{drugId}
 */
public record Drug(
    String drugId,
    String name,                    // "Testosterone Cypionate"
    List<String> aliases,           // ["Test Cyp", "Depo-Testosterone"]
    DrugCategory category,
    DrugForm form,
    String defaultUnit,             // "mg", "mcg", "IU", "ml"
    List<String> commonDoses,       // ["100mg", "200mg"]
    String imageUrl,                // GCS CDN URL (null if generating)
    // All generated/uploaded image URLs; imageUrl is the active one and is always a member (or null).
    List<String> imageCandidates,
    String imageFallback,           // Generic form image URL
    List<String> suggestedMarkers,  // ["TESTOSTERONE", "FREE_TESTOSTERONE"]
    String description,             // Brief description of the drug
    Instant createdAt,
    Instant updatedAt,
    // When non-null, this row is an alias of the drugId it points to.
    // Aliases are hidden from search/findAll; admin merge sets this.
    String aliasOfDrugId
) {}
