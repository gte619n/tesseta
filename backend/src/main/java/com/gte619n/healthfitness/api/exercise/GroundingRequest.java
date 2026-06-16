package com.gte619n.healthfitness.api.exercise;

import java.util.List;

/**
 * Body for {@code PUT /api/admin/exercises/{id}/grounding} (IMPL-20): the
 * persisted set of image URLs (own GCS candidates and/or external reference
 * URLs) used as regeneration pose references. A null/empty list clears it.
 */
public record GroundingRequest(List<String> imageUrls) {}
