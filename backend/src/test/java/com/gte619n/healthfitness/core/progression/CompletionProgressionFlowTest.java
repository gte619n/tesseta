package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.ExercisePerformanceDigestService;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.LoggedPrescription;
import com.gte619n.healthfitness.testsupport.InMemoryBodyCompositionRepository;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.progression.InMemoryProgressionRepositories;
import com.gte619n.healthfitness.testsupport.workout.InMemoryWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutaggregate.InMemoryWeeklyWorkoutAggregateRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * IMPL-DELOAD-01 P0 functional gate (the RC-2 kill-shot): the completion
 * service and the progression session loop wired together end-to-end — NOT a
 * hand-built event — proving that the engine's target survives completion and
 * therefore (a) the demonstrated-performance override (IMPL-PROG-LOAD-01
 * D13/D14) actually fires, and (b) PredictionLog records the true prescribed
 * load, not the performed one.
 */
class CompletionProgressionFlowTest {

    private static final String USER = "u-flow";
    private static final String PROGRAM = "p1";
    private static final String BENCH = "bench";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);
    private static final Instant NOW = TODAY.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(64800);

    private InMemoryScheduledWorkoutRepository scheduled;
    private InMemoryProgressionRepositories.State states;
    private InMemoryProgressionRepositories.Predictions predictions;
    private InMemoryProgressionRepositories.Observations observations;
    private WorkoutSessionCompletionService completion;

    @BeforeEach
    void setUp() {
        InMemoryExerciseRepository exercises = new InMemoryExerciseRepository();
        exercises.save(bench());
        InMemoryWorkoutProgramRepository programs = new InMemoryWorkoutProgramRepository();
        programs.save(new WorkoutProgram(USER, PROGRAM, "Prog", null, null, ProgramStatus.ACTIVE,
            ProgramSource.MANUAL, TODAY, null, List.of(), List.of(), NOW, NOW, null));
        scheduled = new InMemoryScheduledWorkoutRepository();
        states = new InMemoryProgressionRepositories.State();
        observations = new InMemoryProgressionRepositories.Observations();
        predictions = new InMemoryProgressionRepositories.Predictions();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SessionLoop loop = new SessionLoop(
            states, observations, predictions,
            new LoadingProfileResolver(exercises, new InMemoryBodyCompositionRepository(),
                new InMemoryProgressionRepositories.Profiles()),
            new InMemoryProgressionRepositories.Block(),
            new InMemoryProgressionRepositories.Week(),
            exercises,
            new ExercisePerformanceDigestService(programs, scheduled),
            new ProgressionWriteback(programs, scheduled), clock);

        // The real wiring: completion publishes SessionCompletedEvent; the loop
        // consumes it — same synchronous path production uses.
        completion = new WorkoutSessionCompletionService(
            scheduled, programs, new InMemoryWorkoutRepository(),
            new InMemoryWeeklyWorkoutAggregateRepository(),
            new MetricChangedPublisher(event -> { }),
            event -> {
                if (event instanceof SessionCompletedEvent e) {
                    loop.onSessionCompleted(e.userId(), e.session());
                }
            });
    }

    @Test
    void outperformingThePrescriptionCarriesForwardThroughCompletion() {
        // Live Kalman belief (past warm-up, ≥6 observations).
        states.save(new ProgressionState(USER, BENCH, 250, 5,
            NOW.minusSeconds(86400L * 30), 10, 0, NOW.minusSeconds(86400L), 1));
        // Today's session was PRESCRIBED 140; a future session awaits writeback.
        scheduled.save(session(TODAY, ScheduledStatus.PLANNED, 140.0, null));
        scheduled.save(session(TODAY.plusDays(7), ScheduledStatus.PLANNED, null, null));

        // The athlete actually pressed 190×8 with 2 reps in reserve — through the
        // completion service, exactly like the phone's PUT.
        completion.complete(USER, PROGRAM, TODAY + "_d1", ScheduledStatus.COMPLETED, NOW, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(
                set(190, 8), set(190, 8), set(190, 8)))));

        // Next session matches the demonstrated 190 — NOT the old +5%-of-140 (147).
        Prescription next = firstRx(scheduled.findById(USER, PROGRAM, TODAY.plusDays(7) + "_d1").orElseThrow());
        assertEquals(190.0, next.targetWeightLbs(), 1e-9, "matched demonstrated load");
        assertTrue(next.targetWeightLbs() > 147, "the stripped-target fallback would have capped at 147");

        // PredictionLog records the TRUE prescribed load (140), not the performed 190.
        List<PredictionLog> logs = predictions.findByUser(USER);
        assertFalse(logs.isEmpty());
        assertTrue(logs.stream().allMatch(l -> Math.abs(l.prescribedLoad() - 140.0) < 1e-9),
            "prescribedLoad must be the prescription (140), got: "
                + logs.stream().map(PredictionLog::prescribedLoad).toList());
    }

    // ---- fixtures ----

    private static LoggedSet set(double weight, int reps) {
        return new LoggedSet(weight, reps, null, 120, NOW, null, 2.0, RirSource.REPORTED);
    }

    private ScheduledWorkout session(
        LocalDate date, ScheduledStatus status, Double targetLbs, List<LoggedSet> logged) {
        Prescription rx = targetLbs == null
            ? new Prescription(BENCH, 0, 3, 8, 12, null, null, 120, null, null, null, logged)
            : new Prescription(BENCH, 0, 3, 8, 12, null, null, 120, null, null, null, logged,
                targetLbs, "engine");
        WorkoutDay day = new WorkoutDay("d1", "Day 1", DayOfWeek.MON, null, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx))));
        return new ScheduledWorkout(USER, PROGRAM, date + "_d1", date, "ph1", "d1", "Day 1", 1, false,
            null, status, day, null, null, null);
    }

    private static Prescription firstRx(ScheduledWorkout sw) {
        return sw.session().blocks().get(0).prescriptions().get(0);
    }

    private static Exercise bench() {
        return new Exercise(BENCH, "Barbell Bench Press", "barbell bench press", List.of(),
            MovementPattern.PUSH_HORIZONTAL, List.of("chest"), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), List.of(),
            List.of(BlockType.MAIN), null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }
}
