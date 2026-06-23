package com.gte619n.healthfitness.api.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetRepository;
import com.gte619n.healthfitness.core.nutrition.MacroTargetService;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A goal applies its linked program's nutrition guidance as the macro target,
 * preferring the active phase's guidance and re-deriving calories from the
 * macros (the {@link MacroTargetService} invariant).
 */
class GoalNutritionServiceTest {

    private static final String USER = "u1";
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryWorkoutProgramRepository programs = new InMemoryWorkoutProgramRepository();
    private final AtomicReference<MacroTarget> savedTarget = new AtomicReference<>();

    private final MacroTargetRepository targetRepo = new MacroTargetRepository() {
        @Override public Optional<MacroTarget> findActive(String userId) {
            return Optional.ofNullable(savedTarget.get());
        }

        @Override public void save(MacroTarget target) {
            savedTarget.set(target);
        }

        @Override public List<MacroTarget> findAll(String userId) {
            return savedTarget.get() == null ? List.of() : List.of(savedTarget.get());
        }
    };

    private final WorkoutProgramService programService = new WorkoutProgramService(programs);
    private final MacroTargetService targetService =
        new MacroTargetService(targetRepo, new MetricChangedPublisher(event -> { }));
    private final GoalNutritionService service =
        new GoalNutritionService(programService, targetService);

    @Test
    void appliesActivePhaseGuidance_withCaloriesDerivedFromMacros() {
        // Active phase guidance: 200P/280C/80F (kcal 9999 is ignored — derived
        // to 4·200 + 4·280 + 9·80 = 2640).
        programs.save(program("g1", ProgramStatus.ACTIVE,
            new NutritionGuidance(9999, 200, 280, 80, "surplus"),
            null));

        Macros applied = service.applyToTarget(USER, "g1").orElseThrow();

        assertEquals(2640.0, applied.caloriesKcal(), 1e-9);
        assertEquals(200.0, applied.proteinGrams(), 1e-9);
        assertEquals(280.0, applied.carbsGrams(), 1e-9);
        assertEquals(80.0, applied.fatGrams(), 1e-9);
        // Persisted as the active target.
        assertEquals(2640.0, savedTarget.get().macros().caloriesKcal(), 1e-9);
    }

    @Test
    void fallsBackToProgramLevelGuidanceWhenActivePhaseHasNone() {
        programs.save(program("g1", ProgramStatus.ACTIVE,
            null,
            new NutritionGuidance(2000, 150, 200, 60, "maintenance")));

        NutritionGuidance resolved = service.guidanceForGoal(USER, "g1").orElseThrow();

        assertEquals(150, resolved.proteinG());
        assertEquals(200, resolved.carbsG());
    }

    @Test
    void emptyWhenGoalHasNoLinkedProgram() {
        programs.save(program("other-goal", ProgramStatus.ACTIVE,
            new NutritionGuidance(2000, 150, 200, 60, null), null));

        assertTrue(service.applyToTarget(USER, "g1").isEmpty());
        // Nothing written.
        assertTrue(savedTarget.get() == null);
    }

    private WorkoutProgram program(
        String goalId,
        ProgramStatus status,
        NutritionGuidance phaseGuidance,
        NutritionGuidance programGuidance
    ) {
        ProgramPhase phase = new ProgramPhase(
            "ph1", "Accumulation", null, 0, ProgramPhaseStatus.ACTIVE,
            4, null, null, null, null, List.of(), phaseGuidance);
        return new WorkoutProgram(
            USER, "p1", "Program", null, goalId, status, ProgramSource.MANUAL,
            null, null, List.of("ph1"), List.of(phase), T, T, null, programGuidance);
    }
}
