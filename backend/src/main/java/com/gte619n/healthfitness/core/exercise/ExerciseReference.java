package com.gte619n.healthfitness.core.exercise;

import java.util.List;

/**
 * A public-library match for an exercise (added Jun 2026; see commit
 * {@code 140eba0}). Read by the IMPL-19 frame planner and the media generator
 * for reference grounding; its images are generation input only and are never
 * stored or displayed.
 *
 * <p>{@code images} are grounding-only URLs (fedb pose pairs today);
 * {@code groundingImages} is an optional cache of resolved/non-fedb grounding
 * URLs (e.g. a Wikipedia lead image) the planner may populate so the generator
 * does not re-resolve them. Nullable.
 */
public record ExerciseReference(
    String url,
    String source,                 // jefit | rb100 | fedb | yoga
    String name,
    Double score,                  // nullable
    String match,                  // name | simplified
    List<String> images,           // grounding-only URLs (fedb pairs today)
    List<String> groundingImages   // optional resolved/cached grounding URLs; nullable
) {}
