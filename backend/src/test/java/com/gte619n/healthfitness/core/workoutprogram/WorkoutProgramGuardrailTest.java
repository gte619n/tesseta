package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the IMPL-18 guardrails added to {@link WorkoutProgramValidator}: the
 * hard weight-above-e1RM error (blocks commit) and the soft volume/deload/ramp
 * warnings (override-able, R1). Bodyweight exercises keep the equipment check
 * out of the way so the new rules are isolated.
 */
class WorkoutProgramGuardrailTest {

    private static final String USER = "u-guard";
    private static final String GYM = "gym-1";
    private static final LocalDate TODAY = LocalDate.now();

    private InMemoryExerciseRepository exercises;
    private InMemoryWorkoutProgramRepository programs;
    private InMemoryScheduledWorkoutRepository scheduled;
    private WorkoutProgramValidator validator;

    @BeforeEach
    void setUp() {
        exercises = new InMemoryExerciseRepository();
        programs = new InMemoryWorkoutProgramRepository();
        scheduled = new InMemoryScheduledWorkoutRepository();
        LocationRepository locations = new FakeLocations();
        ExerciseAvailabilityService availability = new ExerciseAvailabilityService(exercises, locations, true);
        TrainingScienceScaffold science = new TrainingScienceScaffold();
        ExercisePerformanceDigestService digests = new ExercisePerformanceDigestService(programs, scheduled);
        validator = new WorkoutProgramValidator(exercises, availability, science, digests);
    }

    @Test
    void prescribingAboveEstimated1RmIsAHardError() {
        exercises.save(exercise("bench", "chest"));
        seedHistory("bench", 185.0, 5);    // e1RM ~215.8

        // Prescribe 300 lb — well above the e1RM.
        WorkoutProgram program = oneDayProgram(List.of(rx("bench", 3, 300.0)), 3, null);
        List<String> issues = validator.validate(USER, program);

        assertTrue(issues.stream().anyMatch(i -> i.contains("exceeds your estimated 1RM")),
            () -> "expected an e1RM error, got " + issues);

        // A sane load at/under e1RM passes the weight check.
        WorkoutProgram sane = oneDayProgram(List.of(rx("bench", 3, 200.0)), 3, null);
        assertFalse(validator.validate(USER, sane).stream().anyMatch(i -> i.contains("estimated 1RM")));
    }

    @Test
    void programWithNoTrainingDaysIsRejected() {
        exercises.save(exercise("bench", "chest"));
        // A phase with weeks but no days materializes zero sessions on activate —
        // IMPL-STAB G2 blocks it with an actionable issue instead of a silent
        // empty "This week".
        WorkoutProgram noDays = program(List.of(phase("Phase", 4, null, List.of())));
        List<String> issues = validator.validate(USER, noDays);
        assertTrue(issues.stream().anyMatch(i -> i.contains("no training days to schedule")),
            () -> "expected a no-training-days error, got " + issues);

        // A program with at least one schedulable day passes this guard.
        WorkoutProgram withDay = oneDayProgram(List.of(rx("bench", 3, null)), 4, null);
        assertFalse(validator.validate(USER, withDay).stream()
            .anyMatch(i -> i.contains("no training days to schedule")));
    }

    @Test
    void weeklyVolumePastMrvWarns() {
        exercises.save(exercise("bench", "chest")); // chest MRV = 22
        // 8 sets x 3 days = 24 weekly chest sets > MRV.
        WorkoutProgram program = threeDayProgram("bench", 8, 3, null);
        List<String> warnings = validator.warnings(USER, program);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("chest") && w.contains("MRV")),
            () -> "expected an MRV warning, got " + warnings);
    }

    @Test
    void longPhaseWithoutDeloadWarns() {
        exercises.save(exercise("bench", "chest"));
        WorkoutProgram fiveWeekNoDeload = oneDayProgram(List.of(rx("bench", 3, null)), 5, null);
        assertTrue(validator.warnings(USER, fiveWeekNoDeload).stream()
            .anyMatch(w -> w.contains("no deload")));

        WorkoutProgram withDeload = oneDayProgram(List.of(rx("bench", 3, null)), 5, 5);
        assertFalse(validator.warnings(USER, withDeload).stream().anyMatch(w -> w.contains("no deload")));
    }

    @Test
    void steepPhaseToPhaseVolumeRampWarns() {
        exercises.save(exercise("bench", "chest"));
        // Phase 1: 1 day x 3 sets = 3 weekly sets. Phase 2: 1 day x 9 sets = 9 — a 200% jump.
        ProgramPhase p1 = phase("Base", 4, 4, List.of(day(List.of(rx("bench", 3, null)))));
        ProgramPhase p2 = phase("Build", 4, 4, List.of(day(List.of(rx("bench", 9, null)))));
        WorkoutProgram program = program(List.of(p1, p2));
        assertTrue(validator.warnings(USER, program).stream().anyMatch(w -> w.contains("jumps weekly volume")),
            () -> "expected a ramp warning");
    }

    // ---- fixtures ----

    private void seedHistory(String exerciseId, double weight, int reps) {
        programs.save(new WorkoutProgram(USER, "hist", "hist", null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, null, null, null, List.of(), null, null, null));
        WorkoutDay day = new WorkoutDay("d1", "Day", DayOfWeek.WED, GYM, 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(
                new Prescription(exerciseId, 0, 3, 5, 8, null, null, 120, null, null, null,
                    List.of(new LoggedSet(weight, reps, 8.0, 120,
                        TODAY.minusDays(7).atTime(18, 0).toInstant(ZoneOffset.UTC))))))));
        scheduled.save(new ScheduledWorkout(USER, "hist", TODAY.minusDays(7) + "_d1", TODAY.minusDays(7),
            "ph1", "d1", "Day", 1, false, GYM, ScheduledStatus.COMPLETED, day,
            TODAY.minusDays(7).atTime(18, 0).toInstant(ZoneOffset.UTC), 3600));
    }

    private static Prescription rx(String exerciseId, int sets, Double targetWeightLbs) {
        return new Prescription(exerciseId, 0, sets, 5, 8, null, null, 120, null, null, null, null,
            targetWeightLbs, targetWeightLbs == null ? null : "test");
    }

    private static WorkoutDay day(List<Prescription> rxs) {
        return new WorkoutDay("d1", "Day", DayOfWeek.WED, GYM, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, rxs)));
    }

    private static ProgramPhase phase(String title, int weeks, Integer deloadWeek, List<WorkoutDay> days) {
        return new ProgramPhase("ph_" + title, title, "focus", 0, ProgramPhaseStatus.ACTIVE, weeks,
            deloadWeek, null, null, null, days);
    }

    private static WorkoutProgram program(List<ProgramPhase> phases) {
        return new WorkoutProgram(USER, "p1", "Prog", null, null, ProgramStatus.DRAFT,
            ProgramSource.AI_ASSISTED, null, null, List.of(), phases, null, null, null);
    }

    private static WorkoutProgram oneDayProgram(List<Prescription> rxs, int weeks, Integer deloadWeek) {
        return program(List.of(phase("Phase", weeks, deloadWeek, List.of(day(rxs)))));
    }

    /** A program whose single phase has {@code dayCount} identical days. */
    private static WorkoutProgram threeDayProgram(String exerciseId, int setsPerDay, int dayCount, Integer deloadWeek) {
        List<WorkoutDay> days = new ArrayList<>();
        for (int i = 0; i < dayCount; i++) {
            days.add(new WorkoutDay("d" + i, "Day" + i, DayOfWeek.values()[i], GYM, i,
                List.of(new Block("b" + i, BlockType.MAIN, "Main", 0, List.of(rx(exerciseId, setsPerDay, null))))));
        }
        return program(List.of(phase("Phase", 4, deloadWeek, days)));
    }

    private static Exercise exercise(String id, String muscle) {
        return new Exercise(id, id, id, List.of(), MovementPattern.OTHER, List.of(muscle), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), List.of(), List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }

    /** Bodyweight gym: a single location with no equipment, so anything is executable. */
    private static final class FakeLocations implements LocationRepository {
        private final Location gym = new Location(USER, GYM, "Home", null, null, true, Map.of(),
            List.of(), List.of(), Map.of(), true, true, Instant.now(), Instant.now());

        @Override public Optional<Location> findById(String userId, String locationId) {
            return GYM.equals(locationId) ? Optional.of(gym) : Optional.empty();
        }
        @Override public List<Location> findByUser(String userId, boolean includeInactive) { return List.of(gym); }
        @Override public void save(Location location) { }
        @Override public void delete(String userId, String locationId) { }
        @Override public void setDefault(String userId, String locationId) { }
        @Override public List<Location> findAllReferencing(String equipmentId) { return List.of(); }
    }
}
