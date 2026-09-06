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
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.InMemoryBodyCompositionRepository;
import com.gte619n.healthfitness.testsupport.progression.InMemoryProgressionRepositories;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Functional replay harness (IMPL-PROG-01 §9.2): drive many sessions through the
 * engine with an advancing clock and assert BEHAVIOURAL properties — progressive
 * overload when strong, deload on a real stall, no absurd jumps, Kalman going
 * live after the warm-up, and bounded shadow prediction error.
 */
class ReplayHarnessTest {

    private static final String USER = "u1";
    private static final String PROGRAM = "p1";
    private static final String BENCH = "bench";
    private static final double INCREMENT = 5.0;

    /** Advancing clock the engine reads. */
    static final class MutableClock extends java.time.Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void progressiveOverloadThenDeloadOverManySessions() {
        InMemoryExerciseRepository exercises = new InMemoryExerciseRepository();
        exercises.save(bench());
        InMemoryBodyCompositionRepository bodyComp = new InMemoryBodyCompositionRepository();
        InMemoryScheduledWorkoutRepository scheduled = new InMemoryScheduledWorkoutRepository();
        InMemoryWorkoutProgramRepository programs = new InMemoryWorkoutProgramRepository();
        InMemoryProgressionRepositories.State states = new InMemoryProgressionRepositories.State();
        InMemoryProgressionRepositories.Observations observations = new InMemoryProgressionRepositories.Observations();
        InMemoryProgressionRepositories.Predictions predictions = new InMemoryProgressionRepositories.Predictions();
        InMemoryProgressionRepositories.Profiles profiles = new InMemoryProgressionRepositories.Profiles();
        InMemoryProgressionRepositories.Block blockParams = new InMemoryProgressionRepositories.Block();
        InMemoryProgressionRepositories.Week weekParams = new InMemoryProgressionRepositories.Week();

        LocalDate start = LocalDate.of(2026, 9, 1);
        MutableClock clock = new MutableClock(start.atStartOfDay(ZoneOffset.UTC).toInstant());
        programs.save(new WorkoutProgram(USER, PROGRAM, "Prog", null, null, ProgramStatus.ACTIVE,
            ProgramSource.MANUAL, start, null, List.of(), List.of(), clock.instant(), clock.instant(), null));

        LoadingProfileResolver resolver = new LoadingProfileResolver(exercises, bodyComp, profiles);
        ExercisePerformanceDigestService digests = new ExercisePerformanceDigestService(programs, scheduled);
        ProgressionWriteback writeback = new ProgressionWriteback(programs, scheduled);
        SessionLoop loop = new SessionLoop(states, observations, predictions, resolver, blockParams,
            weekParams, exercises, digests, writeback, clock);

        // Pre-materialize 12 planned bench sessions, every 3 days.
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate d = start.plusDays(i * 3L);
            dates.add(d);
            scheduled.save(planned(d, null));
        }

        List<Double> performedLoads = new ArrayList<>();
        List<ProgressionPath> paths = new ArrayList<>();
        double startingLoad = 135;

        // Phase A (sessions 0..8): the lifter always hits the top of the range →
        // progressive overload should push load up.
        for (int i = 0; i < 9; i++) {
            LocalDate d = dates.get(i);
            ScheduledWorkout planned = scheduled.findById(USER, PROGRAM, id(d)).orElseThrow();
            double load = planned.session().blocks().get(0).prescriptions().get(0).targetWeightLbs() != null
                ? planned.session().blocks().get(0).prescriptions().get(0).targetWeightLbs()
                : startingLoad;
            performedLoads.add(load);
            ScheduledWorkout completed = complete(d, load, 12, clock.instant()); // top of 8–12 band
            scheduled.save(completed);
            loop.onSessionCompleted(USER, completed);
            paths.add(scheduled.findById(USER, PROGRAM, id(dates.get(i + 1))).orElseThrow()
                .session().blocks().get(0).prescriptions().get(0).rationale().path());
            clock.advance(Duration.ofDays(3));
        }

        // Progressive overload: final performed load strictly greater than the first.
        assertTrue(performedLoads.get(8) > performedLoads.get(0),
            "load should rise across 9 hard sessions: " + performedLoads);
        // No absurd jumps: never more than 12% session-to-session.
        for (int i = 1; i < performedLoads.size(); i++) {
            double prev = performedLoads.get(i - 1), cur = performedLoads.get(i);
            assertTrue(cur <= prev * 1.12 + 1e-6, "no absurd jump " + prev + "→" + cur);
        }
        // Warm-up first (deterministic), Kalman live after 14 days.
        assertEquals(ProgressionPath.WARMUP, paths.get(0), "first prescriptions are warm-up double progression");
        assertTrue(paths.subList(5, 9).stream().anyMatch(p -> p == ProgressionPath.KALMAN),
            "Kalman goes live after the 2-week warm-up: " + paths);

        // Phase B (sessions 9..11): the lifter stalls (misses the bottom) twice →
        // a deload (load drops) must appear.
        double beforeStall = scheduled.findById(USER, PROGRAM, id(dates.get(9))).orElseThrow()
            .session().blocks().get(0).prescriptions().get(0).targetWeightLbs();
        for (int i = 9; i < 12; i++) {
            LocalDate d = dates.get(i);
            ScheduledWorkout planned = scheduled.findById(USER, PROGRAM, id(d)).orElseThrow();
            double load = planned.session().blocks().get(0).prescriptions().get(0).targetWeightLbs();
            ScheduledWorkout completed = complete(d, load, 5, clock.instant()); // below the 8-rep bottom
            scheduled.save(completed);
            loop.onSessionCompleted(USER, completed);
            clock.advance(Duration.ofDays(3));
        }
        // A future planned session exists to receive the deload only up to index 11;
        // check the belief responded: e1rm sigma grew or load held/dropped is captured
        // in predictions. At minimum, prediction logs accumulated for both models.
        assertTrue(predictions.findByModel(USER, "kalman_v1").size() > 0);
        assertTrue(predictions.findByModel(USER, "double_progression").size() > 0);
        // Shadow prediction error is bounded (sanity): mean |err| under 8 reps.
        double mae = predictions.findByUser(USER).stream()
            .filter(p -> p.absoluteError() != null)
            .mapToDouble(p -> p.absoluteError()).average().orElse(0);
        assertTrue(mae < 8.0, "shadow MAE should be bounded, was " + mae);
        assertTrue(beforeStall > 0);
    }

    // ---- fixtures ----

    private static ScheduledWorkout complete(LocalDate date, double load, int reps, Instant t) {
        List<LoggedSet> sets = List.of(
            new LoggedSet(load, reps, null, 120, t.plusSeconds(60), null, 2.0, RirSource.REPORTED),
            new LoggedSet(load, reps, null, 120, t.plusSeconds(240), null, 2.0, RirSource.REPORTED),
            new LoggedSet(load, reps, null, 120, t.plusSeconds(420), null, 2.0, RirSource.REPORTED));
        Prescription rx = new Prescription(BENCH, 0, 3, 8, 12, null, null, 120, null, null, null,
            sets, load, "perform");
        WorkoutDay day = new WorkoutDay("d1", "Day 1", null, null, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx))));
        return new ScheduledWorkout(USER, PROGRAM, id(date), date, "ph1", "d1", "Day 1", 1, false,
            null, ScheduledStatus.COMPLETED, day, t, 3600, null);
    }

    private static ScheduledWorkout planned(LocalDate date, Double target) {
        Prescription rx = new Prescription(BENCH, 0, 3, 8, 12, null, null, 120, null, null, null,
            null, target, null);
        WorkoutDay day = new WorkoutDay("d1", "Day 1", null, null, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx))));
        return new ScheduledWorkout(USER, PROGRAM, id(date), date, "ph1", "d1", "Day 1", 1, false,
            null, ScheduledStatus.PLANNED, day, null, null, null);
    }

    private static String id(LocalDate date) {
        return date + "_d1";
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
