package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link ExercisePerformanceDigestService}: the IMPL-18 per-exercise
 * rollup from COMPLETED logged sets (Epley e1RM, weight-only low-confidence
 * floor, staleness, trailing-vs-prior volume) and the {@code get_exercise_history}
 * backing {@link #history}. Uses the in-memory testsupport repos; sessions are
 * dated relative to {@code LocalDate.now()} so staleness buckets are stable.
 */
class ExercisePerformanceDigestServiceTest {

    private static final String USER = "u-digest";
    private static final LocalDate TODAY = LocalDate.now();

    private InMemoryWorkoutProgramRepository programs;
    private InMemoryScheduledWorkoutRepository scheduled;
    private ExercisePerformanceDigestService service;

    @BeforeEach
    void setUp() {
        programs = new InMemoryWorkoutProgramRepository();
        scheduled = new InMemoryScheduledWorkoutRepository();
        service = new ExercisePerformanceDigestService(programs, scheduled);
    }

    @Test
    void epleyE1rmFromWeightAndReps() {
        seedProgram("p1");
        // 185 x 5 ten days ago: e1RM = 185 * (1 + 5/30) = 215.833...
        seedCompleted("p1", TODAY.minusDays(10), "bench",
            List.of(new LoggedSet(135.0, 8, 7.0, 90, instant(TODAY.minusDays(10))),
                new LoggedSet(185.0, 5, 9.0, 120, instant(TODAY.minusDays(10)))));

        ExerciseDigest d = service.digest(USER, List.of("bench")).get("bench");

        assertEquals(185.0 * (1 + 5 / 30.0), d.estimated1Rm(), 1e-9);
        assertEquals(185.0, d.bestRecentWeightLbs(), 1e-9);
        assertEquals(5, d.bestRecentReps());
        assertFalse(d.lowConfidence());
        assertEquals(8.0, d.typicalRpe(), 1e-9);   // mean of 7 and 9
        assertEquals(5, d.minReps());
        assertEquals(8, d.maxReps());
        assertEquals(TODAY.minusDays(10), d.lastPerformed());
    }

    @Test
    void weightOnlyImportedRowYieldsLowConfidenceFloor() {
        seedProgram("imported-history");
        // Imported rows: weight only, reps null. e1RM floors to the top weight.
        seedCompleted("imported-history", TODAY.minusDays(5), "squat",
            List.of(new LoggedSet(225.0, null, null, null, null),
                new LoggedSet(245.0, null, null, null, null)));

        ExerciseDigest d = service.digest(USER, List.of("squat")).get("squat");

        assertEquals(245.0, d.estimated1Rm(), 1e-9);
        assertEquals(245.0, d.bestRecentWeightLbs(), 1e-9);
        assertNull(d.bestRecentReps());
        assertTrue(d.lowConfidence());
        assertNull(d.typicalRpe());
        assertNull(d.minReps());
        assertNull(d.maxReps());
    }

    @Test
    void stalenessWeeksSinceLastBuckets() {
        seedProgram("p1");
        seedCompleted("p1", TODAY.minusWeeks(8), "deadlift",
            List.of(new LoggedSet(315.0, 3, null, null, null)));
        // A more recent, lighter session sets lastPerformed but not the best e1RM.
        seedCompleted("p1", TODAY.minusWeeks(2), "deadlift",
            List.of(new LoggedSet(135.0, 5, null, null, null)));

        ExerciseDigest d = service.digest(USER, List.of("deadlift")).get("deadlift");

        assertEquals(TODAY.minusWeeks(2), d.lastPerformed());
        assertEquals(2, d.weeksSinceLast());
        // Best e1RM still comes from the heavier 315x3 in-window set.
        assertEquals(315.0 * (1 + 3 / 30.0), d.estimated1Rm(), 1e-9);
        assertEquals(315.0, d.bestRecentWeightLbs(), 1e-9);
        assertEquals(3, d.bestRecentReps());
    }

    @Test
    void trailingVsPriorFourWeekSetCounts() {
        seedProgram("p1");
        // 2 sets inside the last 28 days...
        seedCompleted("p1", TODAY.minusDays(3), "ohp",
            List.of(new LoggedSet(95.0, 8, null, null, null),
                new LoggedSet(95.0, 8, null, null, null)));
        // ...and 3 sets in the 29..56-day prior window.
        seedCompleted("p1", TODAY.minusDays(40), "ohp",
            List.of(new LoggedSet(90.0, 8, null, null, null),
                new LoggedSet(90.0, 8, null, null, null),
                new LoggedSet(90.0, 8, null, null, null)));

        ExerciseDigest d = service.digest(USER, List.of("ohp")).get("ohp");

        assertEquals(2, d.trailing4wkSets());
        assertEquals(3, d.prior4wkSets());
    }

    @Test
    void historyOrdersNewestFirstAndRespectsLimit() {
        seedProgram("p1");
        seedProgram("p2");
        // Same date, two completedAt times — newer completedAt should sort first.
        Instant early = instant(TODAY.minusDays(2)).plusSeconds(0);
        Instant late = instant(TODAY.minusDays(2)).plusSeconds(3600);
        seedCompleted("p1", TODAY.minusDays(2), "row",
            List.of(new LoggedSet(135.0, 10, null, null, early),
                new LoggedSet(155.0, 8, null, null, late)));
        seedCompleted("p2", TODAY.minusDays(20), "row",
            List.of(new LoggedSet(115.0, 12, null, null, instant(TODAY.minusDays(20)))));

        List<ExerciseSetLog> all = service.history(USER, "row", 10);
        assertEquals(3, all.size());
        // Newest date first; within the date, latest completedAt first.
        assertEquals(TODAY.minusDays(2), all.get(0).date());
        assertEquals(155.0, all.get(0).weightLbs(), 1e-9);
        assertEquals(135.0, all.get(1).weightLbs(), 1e-9);
        assertEquals(TODAY.minusDays(20), all.get(2).date());
        assertEquals("p2", all.get(2).programId());

        // Limit truncates after the sort.
        List<ExerciseSetLog> top2 = service.history(USER, "row", 2);
        assertEquals(2, top2.size());
        assertEquals(155.0, top2.get(0).weightLbs(), 1e-9);
        assertEquals(135.0, top2.get(1).weightLbs(), 1e-9);
    }

    @Test
    void digestOmitsExercisesWithoutHistory_andDigestAllCoversEverything() {
        seedProgram("p1");
        seedCompleted("p1", TODAY.minusDays(1), "curl",
            List.of(new LoggedSet(40.0, 12, null, null, null)));

        // Requested id with no history is absent from the map.
        Map<String, ExerciseDigest> req = service.digest(USER, List.of("curl", "never-done"));
        assertEquals(1, req.size());
        assertTrue(req.containsKey("curl"));

        Map<String, ExerciseDigest> all = service.digestAll(USER);
        assertEquals(1, all.size());
        assertTrue(all.containsKey("curl"));
    }

    @Test
    void onlyCompletedSessionsAreScanned() {
        seedProgram("p1");
        // A PLANNED session's logged sets must not contribute.
        WorkoutDay day = day("plan", List.of(new LoggedSet(500.0, 1, null, null, null)));
        scheduled.save(new ScheduledWorkout(
            USER, "p1", TODAY.minusDays(1) + "_d1", TODAY.minusDays(1), "ph1", "d1", "Plan",
            1, false, "gym-1", ScheduledStatus.PLANNED, day, null, null));

        assertTrue(service.digestAll(USER).isEmpty());
    }

    // ---- fixtures ----

    private void seedProgram(String programId) {
        programs.save(new WorkoutProgram(USER, programId, programId, null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, null, null, null, List.of(), null, null, null));
    }

    private void seedCompleted(String programId, LocalDate date, String exerciseId, List<LoggedSet> sets) {
        WorkoutDay day = new WorkoutDay("d1", "Day", DayOfWeek.WED, "gym-1", 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(
                new Prescription(exerciseId, 0, 3, 5, 8, null, null, 120, null, null, null, sets)))));
        scheduled.save(new ScheduledWorkout(
            USER, programId, date + "_d1", date, "ph1", "d1", "Day",
            1, false, "gym-1", ScheduledStatus.COMPLETED, day,
            instant(date), 3600));
    }

    private static WorkoutDay day(String exerciseId, List<LoggedSet> sets) {
        return new WorkoutDay("d1", "Day", DayOfWeek.WED, "gym-1", 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(
                new Prescription(exerciseId, 0, 3, 5, 8, null, null, 120, null, null, null, sets)))));
    }

    private static Instant instant(LocalDate date) {
        return date.atTime(18, 0).toInstant(ZoneOffset.UTC);
    }
}
