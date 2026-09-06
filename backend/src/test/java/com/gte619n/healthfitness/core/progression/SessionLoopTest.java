package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end session-loop test on in-memory fakes (IMPL-PROG-01 §9.2): a
 * completed session updates the belief, logs observations, and writes the next
 * session's prescription into a future PLANNED session.
 */
class SessionLoopTest {

    private static final String USER = "u1";
    private static final String PROGRAM = "p1";
    private static final String BENCH = "bench";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);
    private static final Instant NOW = TODAY.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(64800);

    private InMemoryExerciseRepository exercises;
    private InMemoryBodyCompositionRepository bodyComp;
    private InMemoryScheduledWorkoutRepository scheduled;
    private InMemoryWorkoutProgramRepository programs;
    private InMemoryProgressionRepositories.State states;
    private InMemoryProgressionRepositories.Observations observations;
    private InMemoryProgressionRepositories.Predictions predictions;
    private InMemoryProgressionRepositories.Profiles profiles;
    private InMemoryProgressionRepositories.Block blockParams;
    private InMemoryProgressionRepositories.Week weekParams;
    private SessionLoop loop;

    @BeforeEach
    void setUp() {
        exercises = new InMemoryExerciseRepository();
        exercises.save(bench());
        bodyComp = new InMemoryBodyCompositionRepository();
        scheduled = new InMemoryScheduledWorkoutRepository();
        programs = new InMemoryWorkoutProgramRepository();
        programs.save(activeProgram());
        states = new InMemoryProgressionRepositories.State();
        observations = new InMemoryProgressionRepositories.Observations();
        predictions = new InMemoryProgressionRepositories.Predictions();
        profiles = new InMemoryProgressionRepositories.Profiles();
        blockParams = new InMemoryProgressionRepositories.Block();
        weekParams = new InMemoryProgressionRepositories.Week();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        LoadingProfileResolver resolver = new LoadingProfileResolver(exercises, bodyComp, profiles);
        ExercisePerformanceDigestService digests = new ExercisePerformanceDigestService(programs, scheduled);
        ProgressionWriteback writeback = new ProgressionWriteback(programs, scheduled);
        loop = new SessionLoop(states, observations, predictions, resolver, blockParams, weekParams,
            exercises, digests, writeback, clock);
    }

    @Test
    void completedSessionSeedsBeliefLogsObservationsAndWritesNextPrescription() {
        // Future PLANNED bench session, no target yet.
        scheduled.save(plannedBench(TODAY.plusDays(7)));
        // Completed session today: 135×12,12,12 @ RIR 2 → top of the 8–12 band.
        ScheduledWorkout completed = completedBench(TODAY,
            List.of(set(135, 12), set(135, 12), set(135, 12)));
        scheduled.save(completed);

        loop.onSessionCompleted(USER, completed);

        // Belief seeded from history.
        ProgressionState state = states.find(USER, BENCH).orElseThrow();
        assertTrue(state.e1rmLbs() > 150, "e1rm seeded from digest history");
        assertEquals(1, state.observationCount());
        assertNotNull(state.kalmanEligibleAt());

        // Observations recorded (one per set), last flagged.
        List<SetObservation> obs = observations.findByExercise(USER, BENCH);
        assertEquals(3, obs.size());
        assertTrue(obs.get(2).isLastWorkingSet());

        // Next session's bench prescription written: top hit → +5 lb (140), warm-up path.
        Prescription next = benchPrescription(scheduled.findById(USER, PROGRAM, id(TODAY.plusDays(7))).orElseThrow());
        assertEquals(140.0, next.targetWeightLbs(), 1e-9);
        assertNotNull(next.rationale());
        assertEquals(ProgressionPath.WARMUP, next.rationale().path());
        assertEquals(Direction.UP, next.rationale().direction());

        // Shadow predictions logged for both models.
        assertFalse(predictions.findByUser(USER).isEmpty());
    }

    // ---- fixtures ----

    private static LoggedSet set(double weight, int reps) {
        return new LoggedSet(weight, reps, null, 120, NOW, null, 2.0, RirSource.REPORTED);
    }

    private ScheduledWorkout completedBench(LocalDate date, List<LoggedSet> sets) {
        Prescription rx = new Prescription(BENCH, 0, 3, 8, 12, null, null, 120, null, null, null,
            sets, 135.0, "seed");
        WorkoutDay day = new WorkoutDay("d1", "Day 1", null, null, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx))));
        return new ScheduledWorkout(USER, PROGRAM, id(date), date, "ph1", "d1", "Day 1", 1, false,
            null, ScheduledStatus.COMPLETED, day, NOW, 3600, null);
    }

    private ScheduledWorkout plannedBench(LocalDate date) {
        Prescription rx = new Prescription(BENCH, 0, 3, 8, 12, null, null, 120, null, null, null, null);
        WorkoutDay day = new WorkoutDay("d1", "Day 1", null, null, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx))));
        return new ScheduledWorkout(USER, PROGRAM, id(date), date, "ph1", "d1", "Day 1", 1, false,
            null, ScheduledStatus.PLANNED, day, null, null, null);
    }

    private static Prescription benchPrescription(ScheduledWorkout sw) {
        return sw.session().blocks().get(0).prescriptions().get(0);
    }

    private static String id(LocalDate date) {
        return date + "_d1";
    }

    private static WorkoutProgram activeProgram() {
        return new WorkoutProgram(USER, PROGRAM, "Prog", null, null, ProgramStatus.ACTIVE,
            ProgramSource.MANUAL, TODAY, null, List.of(), List.of(), NOW, NOW, null);
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
