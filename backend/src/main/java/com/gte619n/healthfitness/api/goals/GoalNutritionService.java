package com.gte619n.healthfitness.api.goals;

import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetService;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Lets a goal drive the nutrition module: it sources the goal's linked workout
 * program's {@link NutritionGuidance} (display-only on its own) and writes it as
 * the user's macro target. Lives at the composition layer so it can depend on
 * both the workout-program and nutrition core services without coupling them.
 *
 * <p>Goals stay nutrition-agnostic — the macros come entirely from the linked
 * program; {@link MacroTargetService#setTarget} then re-derives calories from
 * them, so the applied target is internally consistent.
 */
@Service
public class GoalNutritionService {

    private final WorkoutProgramService programs;
    private final MacroTargetService macroTargets;

    public GoalNutritionService(WorkoutProgramService programs, MacroTargetService macroTargets) {
        this.programs = programs;
        this.macroTargets = macroTargets;
    }

    /** The guidance a goal would apply, if any (active phase's, else program-level). */
    public Optional<NutritionGuidance> guidanceForGoal(String userId, String goalId) {
        return programs.findActiveForGoal(userId, goalId)
            .flatMap(WorkoutProgramService::effectiveGuidance);
    }

    /**
     * Apply the goal's linked-program guidance as the user's macro target,
     * returning the saved macros. Empty when the goal has no program guidance.
     */
    public Optional<Macros> applyToTarget(String userId, String goalId) {
        return guidanceForGoal(userId, goalId).map(guidance -> {
            MacroTarget saved = macroTargets.setTarget(userId, toMacros(guidance));
            return saved.macros();
        });
    }

    private static Macros toMacros(NutritionGuidance g) {
        return new Macros(
            g.kcal() == null ? null : g.kcal().doubleValue(),
            g.proteinG() == null ? null : g.proteinG().doubleValue(),
            g.carbsG() == null ? null : g.carbsG().doubleValue(),
            g.fatG() == null ? null : g.fatG().doubleValue(),
            null,
            null);
    }
}
