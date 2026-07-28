package com.gte619n.healthfitness.api.exercise;

/**
 * Body for {@code POST /api/admin/exercises/{id}/grounding/remove}: the single
 * grounding image URL to unlink. If it is one of our own grounding uploads the
 * backing GCS object is permanently deleted; candidate/external URLs are only
 * unlinked from the grounding set.
 */
public record GroundingImageRequest(String imageUrl) {}
