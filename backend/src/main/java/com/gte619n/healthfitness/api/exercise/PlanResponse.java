package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.FrameSpec;
import java.util.List;

/** GET /{id}/plan: the current frame plan plus its review status (IMPL-19). */
public record PlanResponse(List<FrameSpec> demoPlan, ExerciseMediaStatus planStatus) {
    public static PlanResponse from(Exercise e) {
        return new PlanResponse(
            e.demoPlan() == null ? List.of() : e.demoPlan(),
            e.planStatus() == null ? ExerciseMediaStatus.NONE : e.planStatus()
        );
    }
}
