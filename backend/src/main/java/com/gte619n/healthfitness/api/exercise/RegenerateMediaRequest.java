package com.gte619n.healthfitness.api.exercise;

import java.util.List;

/**
 * {@code key} null = regenerate all frames in the plan (IMPL-19).
 *
 * <p>IMPL-20: {@code referenceImageUrls} optionally overrides the persisted
 * {@code groundingImageUrls} for this run. {@code null} ⇒ use the persisted
 * set; an empty list ⇒ explicitly no grounding; a non-null list ⇒ use exactly
 * these URLs.
 */
public record RegenerateMediaRequest(
    String promptOverride,
    String key,
    List<String> referenceImageUrls
) {}
