package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.gte619n.healthfitness.core.workoutprogram.DeloadModifier;
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
 * IMPL-DELOAD-01 P1 functional gate — a faithful replay of the 2026-09-21 prod
 * incident, end-to-end through the completion service: a deload week is stamped
 * genuinely lighter (D1), and completing it can move neither the post-deload
 * trajectory nor the strength belief (D2/D3).
 */
class DeloadWeekTest {

    private static final String USER = "u-deload";
    private static final String PROGRAM = "p1";
    private static final String OHP = "ohp";
    private static final LocalDate WK3 = LocalDate.of(2026, 9, 14);
    private static final LocalDate WK4 = WK3.plusWeeks(1);   // the deload week
    private static final LocalDate WK5 = WK3.plusWeeks(2);
    private static final Instant NOW = WK3.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(64800);

    private InMemoryScheduledWorkoutRepository scheduled;
    private InMemoryProgressionRepositories.State states;
    private InMemoryProgressionRepositories.Observations observations;
    private InMemoryProgressionRepositories.Predictions predictions;
    private WorkoutSessionCompletionService completion;

    @BeforeEach
    void setUp() {
        InMemoryExerciseRepository exercises = new InMemoryExerciseRepository();
        // "Dumbbell Overhead Press" → name-derived increment 5 (per hand).
        exercises.save(ohp());
        InMemoryWorkoutProgramRepository programs = new InMemoryWorkoutProgramRepository();
        programs.save(new WorkoutProgram(USER, PROGRAM, "Prog", null, null, ProgramStatus.ACTIVE,
            ProgramSource.MANUAL, WK3, null, List.of(), List.of(), NOW, NOW, null));
        scheduled = new InMemoryScheduledWorkoutRepository();
        states = new InMemoryProgressionRepositories.State();
        observations = new InMemoryProgressionRepositories.Observations();
        predictions = new InMemoryProgressionRepositories.Predictions();

        SessionLoop loop = new SessionLoop(
            states, observations, predictions,
            new LoadingProfileResolver(exercises, new InMemoryBodyCompositionRepository(),
                new InMemoryProgressionRepositories.Profiles()),
            new InMemoryProgressionRepositories.Block(),
            new InMemoryProgressionRepositories.Week(),
            exercises,
            new ExercisePerformanceDigestService(programs, scheduled),
            new ProgressionWriteback(programs, scheduled),
            Clock.fixed(NOW, ZoneOffset.UTC));

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
    void deloadWeekIsStampedLighterAndCannotMoveTheTrajectory() {
        // Week 3 (normal), week 4 (DELOAD), week 5 (normal) — all planned.
        scheduled.save(session(WK3, false));
        scheduled.save(session(WK4, true));
        scheduled.save(session(WK5, false));

        // ---- Week 3 completes at 40×8,8,8 (in the 8–12 band → HOLD 40) ----
        completion.complete(USER, PROGRAM, id(WK3), ScheduledStatus.COMPLETED, NOW, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(set(40, 8), set(40, 8), set(40, 8)))));

        // Deload week stamped genuinely lighter (D1): sets 3→2 (×0.5), load
        // 40 → 36 → floorToIncrement(36, 5) = 35, path DELOAD.
        Prescription wk4 = firstRx(scheduled.findById(USER, PROGRAM, id(WK4)).orElseThrow());
        assertEquals(35.0, wk4.targetWeightLbs(), 1e-9, "deload load = floor(40×0.9, 5)");
        assertEquals(2, wk4.sets(), "deload sets = max(1, round(3×0.5))");
        assertEquals(ProgressionPath.DELOAD, wk4.rationale().path());
        assertEquals(Direction.DOWN, wk4.rationale().direction());

        // Post-deload week carries the full trajectory target (HOLD 40, 3 sets).
        Prescription wk5 = firstRx(scheduled.findById(USER, PROGRAM, id(WK5)).orElseThrow());
        assertEquals(40.0, wk5.targetWeightLbs(), 1e-9, "trajectory lives on the post-deload week");
        assertEquals(3, wk5.sets());

        ProgressionState beliefBefore = states.find(USER, OHP).orElseThrow();
        int predictionsBefore = predictions.findByUser(USER).size();

        // ---- Week 4 (deload) completes light: 35×12,12 @ RIR 0 ----
        completion.complete(USER, PROGRAM, id(WK4), ScheduledStatus.COMPLETED,
            NOW.plusSeconds(7 * 86400L), 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(set(35, 12), set(35, 12)))));

        // D2: the trajectory is untouched — week 5 still 40 (a naive engine would
        // have read 35×12 as top-of-band and re-prescribed 40 from a 35 base, or
        // worse, dropped it).
        Prescription wk5After = firstRx(scheduled.findById(USER, PROGRAM, id(WK5)).orElseThrow());
        assertEquals(40.0, wk5After.targetWeightLbs(), 1e-9, "deload completion must not move the trajectory");
        assertEquals(3, wk5After.sets());

        // D3: the belief did not move — same estimate, same observation count.
        ProgressionState beliefAfter = states.find(USER, OHP).orElseThrow();
        assertEquals(beliefBefore.e1rmLbs(), beliefAfter.e1rmLbs(), 1e-9);
        assertEquals(beliefBefore.observationCount(), beliefAfter.observationCount());

        // Observations were still recorded, flagged DELOAD.
        List<SetObservation> deloadObs = observations.findByExercise(USER, OHP).stream()
            .filter(o -> id(WK4).equals(o.sessionId())).toList();
        assertEquals(2, deloadObs.size());
        assertTrue(deloadObs.stream().allMatch(o -> o.contextFlags().contains(ContextFlag.DELOAD)),
            "deload sets carry the DELOAD context flag");

        // No shadow predictions for a deload session.
        assertEquals(predictionsBefore, predictions.findByUser(USER).size());
    }

    @Test
    void authoredDeloadModifierOverridesTheDefaults() {
        // The program author set an explicit dose: keep 60% of sets, −20% load.
        scheduled.save(session(WK3, false));
        scheduled.save(sessionWithModifier(WK4, true, new DeloadModifier(0.6, -0.20)));

        completion.complete(USER, PROGRAM, id(WK3), ScheduledStatus.COMPLETED, NOW, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(set(40, 8), set(40, 8), set(40, 8)))));

        Prescription wk4 = firstRx(scheduled.findById(USER, PROGRAM, id(WK4)).orElseThrow());
        // 40 × 0.8 = 32 → floor to 5 = 30; sets round(3×0.6)=2.
        assertEquals(30.0, wk4.targetWeightLbs(), 1e-9);
        assertEquals(2, wk4.sets());
    }

    // ---- fixtures ----

    private static LoggedSet set(double weight, int reps) {
        // RIR present on week-3 sets so the belief seeds; the deload sets reuse
        // it too (RIR value is irrelevant there — the loop skips the update).
        return new LoggedSet(weight, reps, null, 120, NOW, null, 2.0, RirSource.REPORTED);
    }

    private ScheduledWorkout session(LocalDate date, boolean isDeload) {
        return sessionWithModifier(date, isDeload, null);
    }

    private ScheduledWorkout sessionWithModifier(LocalDate date, boolean isDeload, DeloadModifier modifier) {
        Prescription rx = new Prescription(OHP, 0, 3, 8, 12, null, null, 120, null, null, modifier, null);
        WorkoutDay day = new WorkoutDay("d1", "Push", DayOfWeek.MON, null, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx))));
        return new ScheduledWorkout(USER, PROGRAM, id(date), date, "ph1", "d1", "Push", 1, isDeload,
            null, ScheduledStatus.PLANNED, day, null, null, null);
    }

    private static Prescription firstRx(ScheduledWorkout sw) {
        return sw.session().blocks().get(0).prescriptions().get(0);
    }

    private static String id(LocalDate date) {
        return date + "_d1";
    }

    private static Exercise ohp() {
        return new Exercise(OHP, "Dumbbell Overhead Press", "dumbbell overhead press", List.of(),
            MovementPattern.PUSH_VERTICAL, List.of("delts"), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), List.of(),
            List.of(BlockType.MAIN), null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }
}
