package com.gte619n.healthfitness.core.exercise;

import java.util.List;

/**
 * Derives the reviewable frame plan ({@code demoPlan}) for an exercise (IMPL-19).
 * A {@code gemini-3.5-flash} pass reads the exercise plus, when present, the
 * fetched text of its {@link ExerciseReference} page, and returns the distinct
 * positions a learner must see — 1 for a hold, 2 for a standard lift, 3–5 for a
 * skill/flow movement — each with a label, teaching caption, and a per-frame
 * position prompt. Output is clamped to 1–5 frames server-side.
 *
 * <p>Mirrors {@link ExerciseMediaGenerator}: the port lives in {@code core} so
 * the domain depends only on this abstraction; the implementation lives in
 * {@code integrations} (provided separately). The planner does not persist;
 * callers write the result via {@link ExerciseService#savePlan}, which sets
 * {@code planStatus = NEEDS_REVIEW}.
 */
public interface ExerciseFramePlanner {
    /**
     * Produce the frame plan for {@code exercise}. {@code promptOverride} (when
     * non-blank) replaces the built planning instructions verbatim.
     */
    List<FrameSpec> plan(Exercise exercise, String promptOverride);
}
