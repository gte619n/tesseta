package com.gte619n.healthfitness.api.workoutprogram;

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

/** Applying a program's nutrition guidance writes a calorie-consistent macro target. */
class WorkoutProgramNutritionServiceTest {

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
    private final WorkoutProgramNutritionService service = new WorkoutProgramNutritionService(
        programService, new MacroTargetService(targetRepo, new MetricChangedPublisher(e -> { })));

    @Test
    void appliesProgramGuidance_withCaloriesDerivedFromMacros() {
        programs.save(program(new NutritionGuidance(9999, 200, 280, 80, "surplus")));

        Macros applied = service.applyToTarget(USER, "p1").orElseThrow();

        assertEquals(2640.0, applied.caloriesKcal(), 1e-9); // 4·200 + 4·280 + 9·80
        assertEquals(200.0, applied.proteinGrams(), 1e-9);
        assertEquals(2640.0, savedTarget.get().macros().caloriesKcal(), 1e-9);
    }

    @Test
    void emptyWhenProgramHasNoGuidance() {
        programs.save(program(null));

        assertTrue(service.applyToTarget(USER, "p1").isEmpty());
        assertTrue(savedTarget.get() == null);
    }

    private WorkoutProgram program(NutritionGuidance phaseGuidance) {
        ProgramPhase phase = new ProgramPhase(
            "ph1", "Accumulation", null, 0, ProgramPhaseStatus.ACTIVE,
            4, null, null, null, null, List.of(), phaseGuidance);
        return new WorkoutProgram(
            USER, "p1", "Program", null, "g1", ProgramStatus.ACTIVE, ProgramSource.MANUAL,
            null, null, List.of("ph1"), List.of(phase), T, T, null, null);
    }
}
