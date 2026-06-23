package com.gte619n.healthfitness.api.goals;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramNutritionService;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Lets a goal drive the nutrition module: it resolves the goal's linked workout
 * program and delegates to {@link WorkoutProgramNutritionService} to apply that
 * program's {@link NutritionGuidance} (display-only on its own) as the user's
 * macro target.
 *
 * <p>Goals stay nutrition-agnostic — the macros come entirely from the linked
 * program; the macro target service then re-derives calories, so the applied
 * target is internally consistent.
 */
@Service
public class GoalNutritionService {

    private final WorkoutProgramService programs;
    private final WorkoutProgramNutritionService programNutrition;

    public GoalNutritionService(
        WorkoutProgramService programs,
        WorkoutProgramNutritionService programNutrition
    ) {
        this.programs = programs;
        this.programNutrition = programNutrition;
    }

    /** The guidance a goal would apply, if any (from its linked program). */
    public Optional<NutritionGuidance> guidanceForGoal(String userId, String goalId) {
        return programs.findActiveForGoal(userId, goalId)
            .flatMap(p -> programNutrition.guidanceForProgram(userId, p.programId()));
    }

    /**
     * The guidance to surface as the "Update nutrition" action: empty when the
     * goal has no linked-program guidance OR applying it would not change the
     * user's current macro target (so the action isn't offered as a no-op).
     */
    public Optional<NutritionGuidance> guidanceToApply(String userId, String goalId) {
        return programs.findActiveForGoal(userId, goalId)
            .flatMap(p -> programNutrition.guidanceToApply(userId, p.programId()));
    }

    /**
     * Apply the goal's linked-program guidance as the user's macro target,
     * returning the saved macros. Empty when the goal has no program guidance.
     */
    public Optional<Macros> applyToTarget(String userId, String goalId) {
        return programs.findActiveForGoal(userId, goalId)
            .flatMap(p -> programNutrition.applyToTarget(userId, p.programId()));
    }
}
