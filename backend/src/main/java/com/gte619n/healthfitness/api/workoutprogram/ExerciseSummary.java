package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.exercise.DemoFrame;
import com.gte619n.healthfitness.core.exercise.Exercise;
import java.util.List;

/**
 * Compact, read-only exercise info embedded next to each prescription on the
 * deep + calendar responses so clients render a session without an N+1 fetch
 * (see IMPL-15). The prescription's {@code exerciseId} remains the source of
 * truth.
 */
public record ExerciseSummary(
    String exerciseId,
    String name,
    List<String> primaryMuscles,
    List<String> formCues,
    List<DemoFrame> demoFrames
) {
    public static ExerciseSummary from(Exercise e) {
        return new ExerciseSummary(
            e.exerciseId(), e.name(), e.primaryMuscles(), e.formCues(), e.demoFrames());
    }
}
