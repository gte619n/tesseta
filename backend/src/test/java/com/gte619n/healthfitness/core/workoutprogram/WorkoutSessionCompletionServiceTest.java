package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedEvent;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workout.Workout;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregate;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.InvalidSessionLogException;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.LoggedPrescription;
import com.gte619n.healthfitness.testsupport.workout.InMemoryWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutaggregate.InMemoryWeeklyWorkoutAggregateRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link WorkoutSessionCompletionService}: the ADR-0012 completion
 * upsert (transition matrix, validation, idempotent re-PUT) and its fan-out
 * (Workout record, weekly aggregate recompute across programs, metric events).
 * Uses the in-memory testsupport repos and a capturing
 * {@link MetricChangedPublisher}.
 */
class WorkoutSessionCompletionServiceTest {

    private static final String USER = "u-log";
    // 2026-06-03 is a Wednesday; its ISO week starts Monday 2026-06-01.
    private static final LocalDate DATE = LocalDate.of(2026, 6, 3);
    private static final LocalDate WEEK_START = LocalDate.of(2026, 6, 1);
    private static final Instant FINISHED = Instant.parse("2026-06-03T18:30:00Z");

    private InMemoryWorkoutProgramRepository programs;
    private InMemoryScheduledWorkoutRepository scheduled;
    private InMemoryWorkoutRepository workouts;
    private InMemoryWeeklyWorkoutAggregateRepository aggregates;
    private List<MetricChangedEvent> events;
    private WorkoutSessionCompletionService service;

    @BeforeEach
    void setUp() {
        programs = new InMemoryWorkoutProgramRepository();
        scheduled = new InMemoryScheduledWorkoutRepository();
        workouts = new InMemoryWorkoutRepository();
        aggregates = new InMemoryWeeklyWorkoutAggregateRepository();
        events = new ArrayList<>();
        service = new WorkoutSessionCompletionService(
            scheduled, programs, workouts, aggregates, capturingPublisher(events));
    }

    @Test
    void completePersistsActualsAndFansOut() {
        ScheduledWorkout sw = seedPlanned("p1");

        ScheduledWorkout updated = service.complete(
            USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(
                new LoggedSet(135.0, 8, 8.5, 90, FINISHED.minusSeconds(600)),
                new LoggedSet(135.0, 8, null, null, null)))));

        // The upsert lands on the existing ScheduledWorkout.
        assertEquals(ScheduledStatus.COMPLETED, updated.status());
        assertEquals(FINISHED, updated.completedAt());
        assertEquals(3600, updated.durationSeconds());
        Prescription rx0 = prescription(updated, 0);
        assertEquals(2, rx0.loggedSets().size());
        assertEquals(8.5, rx0.loggedSets().get(0).rpe(), 1e-9);
        assertEquals(90, rx0.loggedSets().get(0).restSeconds());
        // The un-logged prescription carries no actuals.
        assertNull(prescription(updated, 1).loggedSets());
        ScheduledWorkout stored = scheduled.findById(USER, "p1", sw.scheduledId()).orElseThrow();
        assertEquals(ScheduledStatus.COMPLETED, stored.status());

        // Fan-out: the session-level Workout record (D5).
        Workout w = workouts.findById(USER, "p1_" + sw.scheduledId()).orElseThrow();
        assertEquals("STRENGTH", w.activityType());
        assertEquals("logger", w.source());
        assertEquals("gym-1", w.locationId());
        assertEquals(FINISHED.minusSeconds(3600), w.startTime());
        assertEquals(FINISHED, w.endTime());

        // Fan-out: the ISO-week aggregate. Both sets have weight+reps.
        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(USER, WEEK_START).orElseThrow();
        assertEquals(1, agg.sessionCount());
        assertEquals(2 * 135.0 * 8, agg.totalTonnage(), 1e-9);

        // Fan-out: one event per workout metric key, published after the saves.
        assertEquals(2, events.size());
        List<String> keys = events.stream().map(MetricChangedEvent::metricKey).toList();
        assertTrue(keys.contains(MetricKey.WORKOUTS_COUNT.key()));
        assertTrue(keys.contains(MetricKey.WORKOUTS_WEEKLY_VOLUME.key()));
        assertTrue(events.stream().allMatch(e -> e.userId().equals(USER)));
    }

    @Test
    void weightOnlySetsCountTowardSessionButNotTonnage() {
        ScheduledWorkout sw = seedPlanned("p1");

        service.complete(USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 1800,
            List.of(new LoggedPrescription("b1", 0, List.of(
                new LoggedSet(225.0, null, null, null, null),   // reps-null: no tonnage
                new LoggedSet(185.0, 5, null, null, null)))));

        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(USER, WEEK_START).orElseThrow();
        assertEquals(1, agg.sessionCount());
        assertEquals(185.0 * 5, agg.totalTonnage(), 1e-9);
    }

    @Test
    void unknownLoggedKeysAreRejected_nothingSaved() {
        ScheduledWorkout sw = seedPlanned("p1");

        InvalidSessionLogException ex = assertThrows(InvalidSessionLogException.class,
            () -> service.complete(USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
                List.of(
                    new LoggedPrescription("nope", 0, List.of(new LoggedSet(95.0, 10, null, null, null))),
                    new LoggedPrescription("b1", 7, List.of(new LoggedSet(95.0, 10, null, null, null))))));

        assertEquals(2, ex.issues().size());
        // Nothing was upserted or fanned out.
        assertEquals(ScheduledStatus.PLANNED,
            scheduled.findById(USER, "p1", sw.scheduledId()).orElseThrow().status());
        assertTrue(workouts.findByUser(USER).isEmpty());
        assertTrue(aggregates.findByWeekStart(USER, WEEK_START).isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void completedRequiresCompletedAtAndDuration() {
        ScheduledWorkout sw = seedPlanned("p1");

        InvalidSessionLogException ex = assertThrows(InvalidSessionLogException.class,
            () -> service.complete(USER, "p1", sw.scheduledId(),
                ScheduledStatus.COMPLETED, null, null, List.of()));

        assertEquals(2, ex.issues().size());
    }

    @Test
    void statusMustBeAnOutcome_plannedRejected() {
        ScheduledWorkout sw = seedPlanned("p1");

        assertThrows(InvalidSessionLogException.class,
            () -> service.complete(USER, "p1", sw.scheduledId(),
                ScheduledStatus.PLANNED, null, null, List.of()));
    }

    @Test
    void unknownSessionThrows() {
        seedProgram("p1");
        assertThrows(IllegalArgumentException.class,
            () -> service.complete(USER, "p1", "2026-06-03_missing",
                ScheduledStatus.COMPLETED, FINISHED, 3600, List.of()));
    }

    @Test
    void rePutReplacesActuals_andAggregateCountsTheSessionOnce() {
        ScheduledWorkout sw = seedPlanned("p1");

        service.complete(USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(new LoggedSet(135.0, 8, null, null, null)))));
        // The edit path is the same upsert: corrected weight, sets moved to rx1.
        ScheduledWorkout updated = service.complete(
            USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3700,
            List.of(new LoggedPrescription("b1", 1, List.of(new LoggedSet(145.0, 6, null, null, null)))));

        assertEquals(3700, updated.durationSeconds());
        assertNull(prescription(updated, 0).loggedSets());
        assertEquals(1, prescription(updated, 1).loggedSets().size());

        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(USER, WEEK_START).orElseThrow();
        assertEquals(1, agg.sessionCount());
        assertEquals(145.0 * 6, agg.totalTonnage(), 1e-9);
        // Still exactly one Workout record (idempotent id).
        assertEquals(1, workouts.findByUser(USER).size());
    }

    @Test
    void skipClearsActuals_removesWorkout_andRecomputesAggregate() {
        ScheduledWorkout sw = seedPlanned("p1");
        service.complete(USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(new LoggedSet(135.0, 8, null, null, null)))));

        // Un-complete: any status may move to SKIPPED (D4).
        ScheduledWorkout updated = service.complete(
            USER, "p1", sw.scheduledId(), ScheduledStatus.SKIPPED, null, null, List.of());

        assertEquals(ScheduledStatus.SKIPPED, updated.status());
        assertNull(updated.completedAt());
        assertNull(updated.durationSeconds());
        assertNull(prescription(updated, 0).loggedSets());
        // The fanned-out Workout is removed and the week recomputes to empty.
        assertEquals(Optional.empty(), workouts.findById(USER, "p1_" + sw.scheduledId()));
        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(USER, WEEK_START).orElseThrow();
        assertEquals(0, agg.sessionCount());
        assertEquals(0.0, agg.totalTonnage(), 1e-9);
    }

    @Test
    void skippedWithLoggedSetsIsRejected() {
        ScheduledWorkout sw = seedPlanned("p1");

        assertThrows(InvalidSessionLogException.class,
            () -> service.complete(USER, "p1", sw.scheduledId(), ScheduledStatus.SKIPPED, null, null,
                List.of(new LoggedPrescription("b1", 0, List.of(new LoggedSet(135.0, 8, null, null, null))))));
    }

    @Test
    void skippedSessionCanBeCompletedLater() {
        ScheduledWorkout sw = seedPlanned("p1");
        service.complete(USER, "p1", sw.scheduledId(), ScheduledStatus.SKIPPED, null, null, List.of());

        ScheduledWorkout updated = service.complete(
            USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(new LoggedSet(135.0, 8, null, null, null)))));

        assertEquals(ScheduledStatus.COMPLETED, updated.status());
        assertTrue(workouts.findById(USER, "p1_" + sw.scheduledId()).isPresent());
        assertEquals(1, aggregates.findByWeekStart(USER, WEEK_START).orElseThrow().sessionCount());
    }

    @Test
    void aggregateRecomputeSpansPrograms() {
        // Two programs with sessions in the same ISO week (imported history +
        // the active program look identical to the aggregate).
        ScheduledWorkout a = seedPlanned("p1");
        ScheduledWorkout b = seedPlanned("p2", DATE.plusDays(2)); // Friday, same week

        service.complete(USER, "p1", a.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(new LoggedSet(100.0, 10, null, null, null)))));
        service.complete(USER, "p2", b.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(new LoggedSet(200.0, 5, null, null, null)))));

        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(USER, WEEK_START).orElseThrow();
        assertEquals(2, agg.sessionCount());
        assertEquals(100.0 * 10 + 200.0 * 5, agg.totalTonnage(), 1e-9);
    }

    @Test
    void aggregateRecomputeKeepsSessionsUnderTombstonedPrograms() {
        // Performed work is history regardless of program state (ADR-0012):
        // a completed session under a since-deleted program must keep counting
        // when a recompute is triggered from another program.
        ScheduledWorkout a = seedPlanned("p1");
        ScheduledWorkout b = seedPlanned("p2", DATE.plusDays(2)); // Friday, same week
        service.complete(USER, "p1", a.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(new LoggedSet(100.0, 10, null, null, null)))));

        programs.delete(USER, "p1"); // soft-delete tombstone

        service.complete(USER, "p2", b.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(new LoggedSet(200.0, 5, null, null, null)))));

        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(USER, WEEK_START).orElseThrow();
        assertEquals(2, agg.sessionCount());
        assertEquals(100.0 * 10 + 200.0 * 5, agg.totalTonnage(), 1e-9);
    }

    @Test
    void outOfRangeSetActualsAreRejected_oneIssuePerField() {
        ScheduledWorkout sw = seedPlanned("p1");

        InvalidSessionLogException ex = assertThrows(InvalidSessionLogException.class,
            () -> service.complete(USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
                List.of(new LoggedPrescription("b1", 0, List.of(
                    new LoggedSet(135.0, 8, null, null, null),          // fine
                    new LoggedSet(-135.0, -8, 10.5, -90, null))))));    // all four bad

        assertEquals(List.of(
            "weightLbs must not be negative at block 'b1' / prescription 0, set 1.",
            "reps must not be negative at block 'b1' / prescription 0, set 1.",
            "rpe must be between 0 and 10 at block 'b1' / prescription 0, set 1.",
            "restSeconds must not be negative at block 'b1' / prescription 0, set 1."),
            ex.issues());
        // Nothing was upserted or fanned out.
        assertEquals(ScheduledStatus.PLANNED,
            scheduled.findById(USER, "p1", sw.scheduledId()).orElseThrow().status());
        assertTrue(workouts.findByUser(USER).isEmpty());
        assertTrue(aggregates.findByWeekStart(USER, WEEK_START).isEmpty());
    }

    @Test
    void negativeRpeIsRejected() {
        ScheduledWorkout sw = seedPlanned("p1");

        InvalidSessionLogException ex = assertThrows(InvalidSessionLogException.class,
            () -> service.complete(USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
                List.of(new LoggedPrescription("b1", 0, List.of(
                    new LoggedSet(135.0, 8, -0.5, null, null))))));

        assertEquals(List.of("rpe must be between 0 and 10 at block 'b1' / prescription 0, set 0."),
            ex.issues());
    }

    @Test
    void boundaryActualsAreLegal_zeroWeightZeroRepsAndFullRpeScale() {
        ScheduledWorkout sw = seedPlanned("p1");

        // 0 weight is bodyweight, 0 reps and 0 rest are inert, RPE spans 0–10
        // inclusive — none of these may reject.
        ScheduledWorkout updated = service.complete(
            USER, "p1", sw.scheduledId(), ScheduledStatus.COMPLETED, FINISHED, 3600,
            List.of(new LoggedPrescription("b1", 0, List.of(
                new LoggedSet(0.0, 0, 0.0, 0, null),
                new LoggedSet(0.0, 12, 10.0, 60, null)))));

        assertEquals(ScheduledStatus.COMPLETED, updated.status());
        assertEquals(2, prescription(updated, 0).loggedSets().size());
        // Zero-weight sets still count toward the session, contributing 0 tonnage.
        WeeklyWorkoutAggregate agg = aggregates.findByWeekStart(USER, WEEK_START).orElseThrow();
        assertEquals(1, agg.sessionCount());
        assertEquals(0.0, agg.totalTonnage(), 1e-9);
    }

    // ---- fixtures ----

    private ScheduledWorkout seedPlanned(String programId) {
        return seedPlanned(programId, DATE);
    }

    private ScheduledWorkout seedPlanned(String programId, LocalDate date) {
        seedProgram(programId);
        WorkoutDay day = new WorkoutDay("d1", "Lower", DayOfWeek.WED, "gym-1", 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(rx("sq", 0), rx("bp", 1)))));
        ScheduledWorkout sw = new ScheduledWorkout(
            USER, programId, date + "_d1", date, "ph1", "d1", "Lower",
            1, false, "gym-1", ScheduledStatus.PLANNED, day, null, null);
        scheduled.save(sw);
        return sw;
    }

    private void seedProgram(String programId) {
        programs.save(new WorkoutProgram(USER, programId, programId, null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, null, null, null, List.of(), null, null, null));
    }

    private static Prescription rx(String exerciseId, int orderIndex) {
        return new Prescription(exerciseId, orderIndex, 3, 5, 8, null, null, 120, null, null, null, null);
    }

    private static Prescription prescription(ScheduledWorkout sw, int orderIndex) {
        return sw.session().blocks().get(0).prescriptions().get(orderIndex);
    }

    private static MetricChangedPublisher capturingPublisher(List<MetricChangedEvent> sink) {
        return new MetricChangedPublisher(event -> {
            if (event instanceof MetricChangedEvent e) sink.add(e);
        });
    }
}
