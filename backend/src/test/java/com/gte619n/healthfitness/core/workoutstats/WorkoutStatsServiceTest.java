package com.gte619n.healthfitness.core.workoutstats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.progression.ProgressionState;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettings;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettingsService;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.progression.InMemoryProgressionRepositories;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutSettingsRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link WorkoutStatsService} — the IMPL-WEB-WORKOUT-01 read-model.
 * Covers BT-1 … BT-11 + BT-13 from the spec (§7.1). The service takes an explicit
 * local {@code today}, so week/current-week boundaries are deterministic without
 * mocking the clock. BT-12 (cache invalidation) lives in
 * {@link WorkoutStatsCacheEvictorTest}.
 */
class WorkoutStatsServiceTest {

    private static final String USER = "u-stats";
    // 2026-06-17 is a Wednesday; its ISO week starts Monday 2026-06-15.
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);
    private static final LocalDate CURRENT_MONDAY = LocalDate.of(2026, 6, 15);

    private InMemoryWorkoutProgramRepository programs;
    private InMemoryScheduledWorkoutRepository scheduled;
    private InMemoryWorkoutSettingsRepository settingsRepo;
    private InMemoryExerciseRepository exercises;
    private InMemoryProgressionRepositories.State states;
    private WorkoutStatsService service;

    @BeforeEach
    void setUp() {
        programs = new InMemoryWorkoutProgramRepository();
        scheduled = new InMemoryScheduledWorkoutRepository();
        settingsRepo = new InMemoryWorkoutSettingsRepository();
        exercises = new InMemoryExerciseRepository();
        states = new InMemoryProgressionRepositories.State();
        service = new WorkoutStatsService(
            programs, scheduled, new WorkoutSettingsService(settingsRepo), exercises, states);
    }

    // ---- BT-1 ----

    @Test
    void emptyHistoryIsAllZeros() {
        WorkoutStats s = service.stats(USER, TODAY, 26);
        assertEquals(0, s.streak().current());
        assertEquals(0, s.streak().longest());
        assertEquals(WorkoutSettings.DEFAULT_TARGET, s.streak().weeklyTarget());
        assertEquals(0, s.streak().thisWeekCompleted());
        assertEquals(26, s.weeklySeries().size());
        assertTrue(s.weeklySeries().stream().allMatch(w -> w.sessions() == 0 && w.tonnageLbs() == 0.0));
        assertEquals(CURRENT_MONDAY, s.weeklySeries().get(25).weekStart());
        assertTrue(s.heatmap().isEmpty());
        assertTrue(s.recentPrs().isEmpty());
        assertTrue(s.trackedExercises().isEmpty());
    }

    // ---- BT-2 ----

    @Test
    void sevenConsecutiveQualifyingWeeksCurrentWeekTwoOfFour() {
        setTarget(4);
        seedProgram("p1");
        // Seven fully-elapsed qualifying weeks (4 sessions each), current week 2/4.
        for (int w = 1; w <= 7; w++) {
            fillWeek("p1", CURRENT_MONDAY.minusWeeks(w), 4);
        }
        fillWeek("p1", CURRENT_MONDAY, 2);

        WorkoutStats.Streak streak = service.stats(USER, TODAY, 26).streak();
        assertEquals(7, streak.current());
        assertEquals(2, streak.thisWeekCompleted());
        assertEquals(7, streak.longest());
    }

    // ---- BT-3 ----

    @Test
    void currentWeekAlreadyMeetingTargetExtendsStreak() {
        setTarget(3);
        seedProgram("p1");
        fillWeek("p1", CURRENT_MONDAY.minusWeeks(1), 3);
        fillWeek("p1", CURRENT_MONDAY.minusWeeks(2), 3);
        fillWeek("p1", CURRENT_MONDAY, 3); // current week already at target

        WorkoutStats.Streak streak = service.stats(USER, TODAY, 26).streak();
        assertEquals(3, streak.thisWeekCompleted());
        assertEquals(3, streak.current()); // 2 elapsed + current
        assertEquals(3, streak.longest());
    }

    // ---- BT-4 ----

    @Test
    void gapWeekStopsCurrentStreakButLongestSpansPreGapRun() {
        setTarget(2);
        seedProgram("p1");
        // Most-recent two elapsed weeks qualify; week 3 back is a gap (0); then a
        // 4-week qualifying run before it.
        fillWeek("p1", CURRENT_MONDAY.minusWeeks(1), 2);
        fillWeek("p1", CURRENT_MONDAY.minusWeeks(2), 2);
        // week 3 back: gap (no sessions)
        for (int w = 4; w <= 7; w++) {
            fillWeek("p1", CURRENT_MONDAY.minusWeeks(w), 2);
        }

        WorkoutStats.Streak streak = service.stats(USER, TODAY, 26).streak();
        assertEquals(2, streak.current());  // stops at the gap
        assertEquals(4, streak.longest());  // the pre-gap run of 4
    }

    // ---- BT-5 ----

    @Test
    void skippedAndPlannedSessionsNeverCount() {
        setTarget(1);
        seedProgram("p1");
        // A SKIPPED and a PLANNED session in the current week: must not count.
        saveSession("p1", CURRENT_MONDAY, "bench", ScheduledStatus.SKIPPED, List.of());
        saveSession("p1", CURRENT_MONDAY.plusDays(1), "bench", ScheduledStatus.PLANNED,
            List.of(new LoggedSet(100.0, 5, null, null, null)));

        WorkoutStats s = service.stats(USER, TODAY, 26);
        assertEquals(0, s.streak().thisWeekCompleted());
        assertEquals(0, s.streak().current());
        assertTrue(s.heatmap().isEmpty());
        assertTrue(s.weeklySeries().stream().allMatch(w -> w.sessions() == 0));
    }

    // ---- BT-6 ----

    @Test
    void weekBoundaryDependsOnSuppliedLocalToday() {
        setTarget(1);
        seedProgram("p1");
        // A session on Sunday 2026-06-14 — the last day of the week starting 06-08.
        fillWeek("p1", LocalDate.of(2026, 6, 8), 1, 6); // one session, offset +6 days = Sunday

        // "Today" is that Sunday (still week 06-08): the session is in the CURRENT week.
        WorkoutStats.Streak sun = service.stats(USER, LocalDate.of(2026, 6, 14), 26).streak();
        assertEquals(LocalDate.of(2026, 6, 8), sun.weekStart());
        assertEquals(1, sun.thisWeekCompleted());

        // "Today" is the next Monday (week 06-15): the same session is now in the
        // last ELAPSED week, so the current week shows zero.
        WorkoutStats.Streak mon = service.stats(USER, LocalDate.of(2026, 6, 15), 26).streak();
        assertEquals(LocalDate.of(2026, 6, 15), mon.weekStart());
        assertEquals(0, mon.thisWeekCompleted());
    }

    // ---- BT-7 ----

    @Test
    void twoProgramsInOneWeekAreSummed() {
        setTarget(4);
        seedProgram("p1");
        seedProgram("p2");
        fillWeek("p1", CURRENT_MONDAY.minusWeeks(1), 2);
        fillWeek("p2", CURRENT_MONDAY.minusWeeks(1), 2); // different days, same week

        WorkoutStats s = service.stats(USER, TODAY, 26);
        // 2 + 2 = 4 in that elapsed week meets the target of 4.
        assertEquals(1, s.streak().current());
        WorkoutStats.WeekPoint elapsed = s.weeklySeries().get(24); // week before current
        assertEquals(CURRENT_MONDAY.minusWeeks(1), elapsed.weekStart());
        assertEquals(4, elapsed.sessions());
    }

    // ---- BT-8 ----

    @Test
    void weightOnlyImportedSetsContributeNoTonnageAndNeverPr() {
        seedProgram("imported");
        // Two weight-only sessions, second heavier — a normal PR would fire, but
        // weight-only rows can never mint one.
        saveSession("imported", CURRENT_MONDAY.minusWeeks(3), "squat", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(225.0, null, null, null, null)));
        saveSession("imported", CURRENT_MONDAY.minusWeeks(1), "squat", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(245.0, null, null, null, null)));

        WorkoutStats s = service.stats(USER, TODAY, 26);
        assertTrue(s.recentPrs().isEmpty());
        assertTrue(s.weeklySeries().stream().allMatch(w -> w.tonnageLbs() == 0.0));

        E1rmHistory h = service.e1rmHistory(USER, "squat");
        assertEquals(2, h.points().size());
        assertEquals(245.0, h.points().get(1).e1rmLbs(), 1e-9);
        assertTrue(h.points().get(1).lowConfidence());
        assertNull(h.points().get(1).reps());
    }

    // ---- BT-9 ----

    @Test
    void personalRecordRulesStrictlyGreaterFirstNeverPrTiesNotPr() {
        seedProgram("p1");
        // Session A: 200x5 (e1RM 233.3) — first ever, never a PR.
        saveSession("p1", CURRENT_MONDAY.minusWeeks(5), "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(200.0, 5, null, null, instant(CURRENT_MONDAY.minusWeeks(5)))));
        // Session B: exact tie (200x5) — not a PR (not strictly greater).
        saveSession("p1", CURRENT_MONDAY.minusWeeks(4), "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(200.0, 5, null, null, instant(CURRENT_MONDAY.minusWeeks(4)))));
        // Session C: 205x5 (e1RM 239.2) — a PR.
        saveSession("p1", CURRENT_MONDAY.minusWeeks(2), "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(205.0, 5, null, null, instant(CURRENT_MONDAY.minusWeeks(2)))));

        List<WorkoutStats.PrPoint> prs = service.stats(USER, TODAY, 26).recentPrs();
        assertEquals(1, prs.size());
        assertEquals("bench", prs.get(0).exerciseId());
        assertEquals(205.0, prs.get(0).weightLbs(), 1e-9);
        assertEquals(CURRENT_MONDAY.minusWeeks(2), prs.get(0).date());
    }

    // ---- BT-10 ----

    @Test
    void tonnageIsSumOfWeightTimesRepsTimedAndBodyweightContributeZero() {
        setTarget(1);
        seedProgram("p1");
        // In one elapsed week: 100x5 (500) + 135x8 (1080) + a bodyweight set (0 weight)
        // + a timed hold (duration, no reps) → total 1580.
        List<LoggedSet> sets = List.of(
            new LoggedSet(100.0, 5, null, null, null),
            new LoggedSet(135.0, 8, null, null, null),
            new LoggedSet(0.0, 12, null, null, null),        // bodyweight → 0
            new LoggedSet(0.0, null, null, null, null, 60)); // timed hold → 0
        saveSession("p1", CURRENT_MONDAY.minusWeeks(1), "bench", ScheduledStatus.COMPLETED, sets);

        WorkoutStats.WeekPoint elapsed = service.stats(USER, TODAY, 26).weeklySeries().get(24);
        assertEquals(CURRENT_MONDAY.minusWeeks(1), elapsed.weekStart());
        assertEquals(1580.0, elapsed.tonnageLbs(), 1e-9);
    }

    // ---- BT-11 ----

    @Test
    void chartDefaultLiftsCapAtFourOnePerPatternByObservationCount() {
        seedExercise("bench", MovementPattern.PUSH_HORIZONTAL);
        seedExercise("incline", MovementPattern.PUSH_HORIZONTAL); // same pattern as bench
        seedExercise("squat", MovementPattern.SQUAT);
        seedExercise("dead", MovementPattern.HINGE);
        seedExercise("row", MovementPattern.PULL_HORIZONTAL);
        seedExercise("pullup", MovementPattern.PULL_VERTICAL);
        seedExercise("curl", MovementPattern.OTHER); // not a main pattern
        seedState("bench", 250, 40);
        seedState("incline", 180, 60); // higher count but same pattern as bench
        seedState("squat", 315, 30);
        seedState("dead", 405, 20);
        seedState("row", 225, 15);
        seedState("pullup", 200, 10);
        seedState("curl", 90, 100);

        List<WorkoutStats.LiftRef> lifts = service.chartDefaultLifts(USER);
        assertEquals(4, lifts.size());
        List<String> ids = lifts.stream().map(WorkoutStats.LiftRef::exerciseId).toList();
        // 'incline' (60 obs) wins its PUSH_HORIZONTAL slot over 'bench' (40);
        // then squat(30), dead(20), row(15). curl is excluded (non-main pattern).
        assertEquals(List.of("incline", "squat", "dead", "row"), ids);
        assertFalse(ids.contains("curl"));
        assertFalse(ids.contains("bench")); // pattern already used by incline
    }

    // ---- BT-12 ----

    @Test
    void trackedExercisesRequireTwoSessionsForAPlottableTrend() {
        seedProgram("p1");
        LocalDate d1 = CURRENT_MONDAY.minusWeeks(3);
        LocalDate d2 = CURRENT_MONDAY.minusWeeks(2);
        LocalDate d3 = CURRENT_MONDAY.minusWeeks(1);
        // 'bench' performed on two days → a plottable trend; 'squat' only once →
        // excluded from the picker (a single point isn't a trend).
        saveSession("p1", d1, "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(185.0, 5, null, null, instant(d1))));
        saveSession("p1", d2, "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(195.0, 5, null, null, instant(d2))));
        saveSession("p1", d3, "squat", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(315.0, 5, null, null, instant(d3))));

        List<String> ids = service.stats(USER, TODAY, 26).trackedExercises().stream()
            .map(WorkoutStats.TrackedExercise::exerciseId).toList();
        assertEquals(List.of("bench"), ids);
    }

    // ---- BT-13 ----

    @Test
    void neighborsBracketSessionByDateAndPrSetKeysBadgeThePrSet() {
        seedProgram("p1");
        LocalDate d1 = CURRENT_MONDAY.minusWeeks(3);
        LocalDate d2 = CURRENT_MONDAY.minusWeeks(2);
        LocalDate d3 = CURRENT_MONDAY.minusWeeks(1);
        saveSession("p1", d1, "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(185.0, 5, null, null, instant(d1))));
        // d2 is a PR: two sets, the second (205x5) is the best → the PR set.
        saveSession("p1", d2, "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(185.0, 5, null, null, instant(d2)),
                new LoggedSet(205.0, 5, null, null, instant(d2))));
        saveSession("p1", d3, "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(135.0, 8, null, null, instant(d3))));

        WorkoutStatsService.Neighbors n = service.neighbors(USER, "p1", scheduledId(d2));
        assertEquals(scheduledId(d1), n.prev().scheduledId());
        assertEquals(scheduledId(d3), n.next().scheduledId());

        // PR set key points at block b1, prescription 0, set index 1 (the 205x5).
        List<String> keys = service.prSetKeys(USER, "p1", scheduledId(d2));
        assertEquals(List.of("b1:0:1"), keys);
        // d3 set no PR; d1 is first-ever (never a PR).
        assertTrue(service.prSetKeys(USER, "p1", scheduledId(d3)).isEmpty());
        assertTrue(service.prSetKeys(USER, "p1", scheduledId(d1)).isEmpty());
    }

    // ---- fixtures ----

    private void setTarget(int target) {
        settingsRepo.save(new WorkoutSettings(USER, target, null, Instant.now()));
    }

    private void seedProgram(String programId) {
        programs.save(new WorkoutProgram(USER, programId, programId, null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, null, null, null, List.of(), null, null, null));
    }

    /** Create {@code count} completed sessions on consecutive days from the Monday. */
    private void fillWeek(String programId, LocalDate weekMonday, int count) {
        for (int i = 0; i < count; i++) {
            fillWeek(programId, weekMonday, 1, i);
        }
    }

    /** One completed session {@code offsetDays} into the week (100x5 → 500 tonnage). */
    private void fillWeek(String programId, LocalDate weekMonday, int count, int offsetDays) {
        LocalDate date = weekMonday.plusDays(offsetDays);
        saveSession(programId, date, "bench", ScheduledStatus.COMPLETED,
            List.of(new LoggedSet(100.0, 5, null, null, instant(date))));
    }

    private void saveSession(
        String programId, LocalDate date, String exerciseId, ScheduledStatus status, List<LoggedSet> sets) {
        List<LoggedSet> logged = new ArrayList<>(sets);
        WorkoutDay day = new WorkoutDay("d1", "Day", DayOfWeek.WED, "gym-1", 0, List.of(
            new Block("b1", BlockType.MAIN, "Main", 0, List.of(
                new Prescription(exerciseId, 0, 3, 5, 8, null, null, 120, null, null, null,
                    logged.isEmpty() ? null : logged)))));
        scheduled.save(new ScheduledWorkout(
            USER, programId, scheduledId(date), date, "ph1", "d1", "Day",
            1, false, "gym-1", status, day,
            status == ScheduledStatus.COMPLETED ? instant(date) : null,
            status == ScheduledStatus.COMPLETED ? 3600 : null, null));
    }

    private static String scheduledId(LocalDate date) {
        return date + "_d1";
    }

    private void seedExercise(String id, MovementPattern pattern) {
        exercises.save(new Exercise(id, id, id, List.of(), pattern, List.of(), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), List.of(), List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of()));
    }

    private void seedState(String exerciseId, double e1rm, int observationCount) {
        states.save(new ProgressionState(
            USER, exerciseId, e1rm, e1rm * 0.05, Instant.now(), observationCount, 0, null, 1));
    }

    private static Instant instant(LocalDate date) {
        return date.atTime(18, 0).toInstant(ZoneOffset.UTC);
    }
}
