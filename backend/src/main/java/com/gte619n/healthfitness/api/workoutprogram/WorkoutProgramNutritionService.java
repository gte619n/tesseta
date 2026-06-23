package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.nutrition.MacroTargetService;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Applies a workout program's {@link NutritionGuidance} (the AI designer's
 * kcal/macro targets, otherwise display-only) as the user's macro target. Lives
 * at the composition layer so it can depend on both the workout-program and
 * nutrition core services. {@link MacroTargetService#setTarget} re-derives
 * calories from the macros, so the applied target is internally consistent.
 */
@Service
public class WorkoutProgramNutritionService {

    private final WorkoutProgramService programs;
    private final MacroTargetService macroTargets;

    public WorkoutProgramNutritionService(WorkoutProgramService programs, MacroTargetService macroTargets) {
        this.programs = programs;
        this.macroTargets = macroTargets;
    }

    /** The guidance a program would apply (active phase's, else program-level), if any. */
    public Optional<NutritionGuidance> guidanceForProgram(String userId, String programId) {
        return programs.findById(userId, programId)
            .flatMap(WorkoutProgramService::effectiveGuidance);
    }

    /** Apply the program's guidance as the macro target; empty when it has none. */
    public Optional<Macros> applyToTarget(String userId, String programId) {
        return guidanceForProgram(userId, programId)
            .map(guidance -> macroTargets.setTarget(userId, toMacros(guidance)).macros());
    }

    /** Map IMPL-18 guidance (integer kcal/grams) to a {@link Macros} bundle. */
    public static Macros toMacros(NutritionGuidance g) {
        return new Macros(
            g.kcal() == null ? null : g.kcal().doubleValue(),
            g.proteinG() == null ? null : g.proteinG().doubleValue(),
            g.carbsG() == null ? null : g.carbsG().doubleValue(),
            g.fatG() == null ? null : g.fatG().doubleValue(),
            null,
            null);
    }
}
