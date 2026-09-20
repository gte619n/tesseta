package com.gte619n.healthfitness.core.adhoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.goals.events.MetricChangedEvent;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.workout.Workout;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.ExerciseDigest;
import com.gte619n.healthfitness.core.workoutprogram.ExercisePerformanceDigestService;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.InvalidSessionLogException;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.LoggedPrescription;
import com.gte619n.healthfitness.testsupport.adhoc.InMemoryAdHocSessionRepository;
import com.gte619n.healthfitness.testsupport.adhoc.InMemoryAdHocWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workout.InMemoryWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutaggregate.InMemoryWeeklyWorkoutAggregateRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The IMPL-ADHOC-01 run-lifecycle DoD journey (AD-05/AD-06/AD-07): run →
 * complete → snapshot + fan-out + counters + weekly stats + digest inclusion,
 * plus replay idempotency and skip. All in-memory, no emulator.
 */
class AdHocSessionServiceTest {

    private static final String USER = "u-run";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 3);      // Wed
    private static final LocalDate WEEK_START = LocalDate.of(2026, 6, 1); // Mon
    private static final Instant FINISHED = Instant.parse("2026-06-03T18:30:00Z");

    private InMemoryAdHocWorkoutRepository adhocRepo;
    private InMemoryAdHocSessionRepository sessionRepo;
    private InMemoryWorkoutRepository flatWorkouts;
    private InMemoryWeeklyWorkoutAggregateRepository aggregates;
    private AdHocWorkoutService workoutService;
    private AdHocSessionService runs;
    private ExercisePerformanceDigestService digests;
    private List<MetricChangedEvent> events;

    @BeforeEach
    void setUp() {
        adhocRepo = new InMemoryAdHocWorkoutRepository();
        sessionRepo = new InMemoryAdHocSessionRepository();
        flatWorkouts = new InMemoryWorkoutRepository();
        aggregates = new InMemoryWeeklyWorkoutAggregateRepository();
        var programs = new InMemoryWorkoutProgramRepository();
        var scheduled = new InMemoryScheduledWorkoutRepository();
        events = new ArrayList<>();

        AdHocCompletedSessionSource source = new AdHocCompletedSessionSource(sessionRepo);
        WorkoutSessionCompletionService completion = new WorkoutSessionCompletionService(
            scheduled, programs, flatWorkouts, aggregates,
            new MetricChangedPublisher(e -> { if (e instanceof MetricChangedEvent me) events.add(me); }),
            event -> { }, List.of(source));

        workoutService = new AdHocWorkoutService(adhocRepo);
        runs = new AdHocSessionService(adhocRepo, sessionRepo, flatWorkouts, completion);
        digests = new ExercisePerformanceDigestService(programs, scheduled, List.of(source));
    }

    private AdHocWorkout template() {
        Prescription rx = new Prescription("ex_bench", 0, 3, 5, 8, null, null, 120, null, null, null, null);
        WorkoutDay day = new WorkoutDay(null, "Full body", null, null, 0,
            List.of(new Block(null, BlockType.MAIN, "Main", 0, List.of(rx))));
        return workoutService.create(new AdHocWorkout(USER, null, "Hotel", null, AdHocSource.AI_GENERATED,
            null, EquipmentContext.empty(), 30, null, List.of("Travel"), false, day, 0, null, null, null));
    }

    private LoggedPrescription oneHeavySet(AdHocWorkout t) {
        String blockId = t.day().blocks().get(0).blockId();
        return new LoggedPrescription(blockId, 0,
            List.of(new LoggedSet(100.0, 5, 8.0, 120, FINISHED)));
    }

    @Test
    void completeMaterializesSnapshotFansOutAndCounts() {
        AdHocWorkout t = template();
        ScheduledWorkout run = runs.complete(USER, t.adhocId(), "aws_1",
            ScheduledStatus.COMPLETED, DATE, FINISHED, 1800, List.of(oneHeavySet(t)), 4);

        // Session persisted with the logged sets on its snapshot.
        assertThat(run.status()).isEqualTo(ScheduledStatus.COMPLETED);
        Optional<ScheduledWorkout> stored = sessionRepo.findById(USER, t.adhocId(), "aws_1");
        assertThat(stored).isPresent();
        assertThat(stored.get().session().blocks().get(0).prescriptions().get(0).loggedSets())
            .hasSize(1);

        // Flat Workout fan-out (source "adhoc", id "{adhocId}_{sessionId}").
        Optional<Workout> flat = flatWorkouts.findById(USER, t.adhocId() + "_aws_1");
        assertThat(flat).isPresent();
        assertThat(flat.get().source()).isEqualTo("adhoc");

        // Template counters.
        AdHocWorkout after = adhocRepo.findById(USER, t.adhocId()).orElseThrow();
        assertThat(after.runCount()).isEqualTo(1);
        assertThat(after.lastPerformedAt()).isNotNull();

        // Weekly aggregate counts the ad-hoc run (D6): 1 session, tonnage 100*5=500.
        var week = aggregates.findByWeekStart(USER, WEEK_START).orElseThrow();
        assertThat(week.sessionCount()).isEqualTo(1);
        assertThat(week.totalTonnage()).isEqualTo(500.0);

        // Digest / e1RM includes the ad-hoc set (feeds last-sets prefill).
        Map<String, ExerciseDigest> digest = digests.digest(USER, List.of("ex_bench"));
        assertThat(digest).containsKey("ex_bench");
        assertThat(digest.get("ex_bench").estimated1Rm()).isGreaterThan(100.0);
        assertThat(digests.lastSessionSets(USER, List.of("ex_bench"))).containsKey("ex_bench");
    }

    @Test
    void replayOfSameSessionIsIdempotent() {
        AdHocWorkout t = template();
        runs.complete(USER, t.adhocId(), "aws_1", ScheduledStatus.COMPLETED, DATE, FINISHED, 1800,
            List.of(oneHeavySet(t)), null);
        // Outbox retry: identical PUT again.
        runs.complete(USER, t.adhocId(), "aws_1", ScheduledStatus.COMPLETED, DATE, FINISHED, 1800,
            List.of(oneHeavySet(t)), null);

        assertThat(adhocRepo.findById(USER, t.adhocId()).orElseThrow().runCount()).isEqualTo(1);
        assertThat(sessionRepo.countByWorkout(USER, t.adhocId())).isEqualTo(1);
        assertThat(aggregates.findByWeekStart(USER, WEEK_START).orElseThrow().sessionCount()).isEqualTo(1);
    }

    @Test
    void twoDistinctRunsCountTwice() {
        AdHocWorkout t = template();
        runs.complete(USER, t.adhocId(), "aws_1", ScheduledStatus.COMPLETED, DATE, FINISHED, 1800,
            List.of(oneHeavySet(t)), null);
        runs.complete(USER, t.adhocId(), "aws_2", ScheduledStatus.COMPLETED, DATE, FINISHED, 1800,
            List.of(oneHeavySet(t)), null);

        assertThat(adhocRepo.findById(USER, t.adhocId()).orElseThrow().runCount()).isEqualTo(2);
        assertThat(aggregates.findByWeekStart(USER, WEEK_START).orElseThrow().sessionCount()).isEqualTo(2);
    }

    @Test
    void skippedRunHasNoFanOutAndDoesNotCount() {
        AdHocWorkout t = template();
        runs.complete(USER, t.adhocId(), "aws_1", ScheduledStatus.SKIPPED, DATE, null, null, List.of(), null);

        assertThat(flatWorkouts.findById(USER, t.adhocId() + "_aws_1")).isEmpty();
        assertThat(adhocRepo.findById(USER, t.adhocId()).orElseThrow().runCount()).isZero();
    }

    @Test
    void unknownTemplateIs404() {
        assertThatThrownBy(() -> runs.complete(USER, "aw_missing", "aws_1",
            ScheduledStatus.COMPLETED, DATE, FINISHED, 1800, List.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedMissingDurationRejected() {
        AdHocWorkout t = template();
        assertThatThrownBy(() -> runs.complete(USER, t.adhocId(), "aws_1",
            ScheduledStatus.COMPLETED, DATE, FINISHED, null, List.of(), null))
            .isInstanceOf(InvalidSessionLogException.class);
    }

    @Test
    void archivedTemplateKeepsPastRunsInHistory() {
        AdHocWorkout t = template();
        runs.complete(USER, t.adhocId(), "aws_1", ScheduledStatus.COMPLETED, DATE, FINISHED, 1800,
            List.of(oneHeavySet(t)), null);
        workoutService.archive(USER, t.adhocId());

        // Template hidden from the active library…
        assertThat(workoutService.list(USER)).isEmpty();
        // …but the completed run is still counted in weekly stats + digest (D12).
        assertThat(aggregates.findByWeekStart(USER, WEEK_START).orElseThrow().sessionCount()).isEqualTo(1);
        assertThat(digests.digest(USER, List.of("ex_bench"))).containsKey("ex_bench");
    }
}
