package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.progression.Confidence;
import com.gte619n.healthfitness.core.progression.Direction;
import com.gte619n.healthfitness.core.progression.PrescriptionRationale;
import com.gte619n.healthfitness.core.progression.ProgressionPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class WorkoutScheduleServiceTest {

    @Test
    void activateMaterializesWeeklyTemplateAcrossWeeksWithDeloadFlagged() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        // One 4-week phase, deload week 4, two training days (MON + THU).
        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of())));
        WorkoutDay thu = new WorkoutDay(null, "Upper", DayOfWeek.THU, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Bench", 0, List.of())));
        ProgramPhase phase = new ProgramPhase(null, "Accumulation", "Hypertrophy", 0, null,
            4, 4, null, null, null, List.of(mon, thu));
        // Start on a Monday safely in the future so activate() — which skips
        // past-dated sessions relative to today — materializes all of them
        // regardless of when this test runs.
        LocalDate start = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
            .plusWeeks(1);
        WorkoutProgram input = new WorkoutProgram("u1", null, "Test", null, null, ProgramStatus.DRAFT,
            ProgramSource.MANUAL, start, null, null, List.of(phase), null, null, null);

        WorkoutProgram created = programService.create(input);
        List<ScheduledWorkout> sessions = scheduleService.activate("u1", created.programId());

        // 4 weeks * 2 days = 8 sessions.
        assertEquals(8, sessions.size());
        // Exactly the week-4 sessions are deload (2 of them).
        long deloads = sessions.stream().filter(ScheduledWorkout::isDeload).count();
        assertEquals(2, deloads);
        assertTrue(sessions.stream().filter(ScheduledWorkout::isDeload)
            .allMatch(s -> s.weekIndexInPhase() == 4));
        // Program flips to ACTIVE.
        assertEquals(ProgramStatus.ACTIVE, programs.findById("u1", created.programId()).orElseThrow().status());
    }

    @Test
    void deloadWeekSnapshotsMaterializeWithReducedSets() {
        // IMPL-DELOAD-01 DD-5: deload-week sessions start with halved working
        // sets (min 1) — warm-up blocks and non-deload weeks untouched.
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Push", DayOfWeek.MON, "home", 0, List.of(
            new Block(null, BlockType.WARMUP, "Warmup", 0, List.of(
                new Prescription("band", 0, 2, 10, 15, null, null, 60, null, null, null, null))),
            new Block(null, BlockType.MAIN, "Main", 1, List.of(
                new Prescription("ohp", 0, 4, 8, 12, null, null, 120, null, null, null, null),
                new Prescription("raise", 1, 1, 12, 15, null, null, 60, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null,
            2, 2, null, null, null, List.of(mon));
        LocalDate start = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
            .plusWeeks(1);
        WorkoutProgram created = programService.create(new WorkoutProgram(
            "u1", null, "Test", null, null, ProgramStatus.DRAFT,
            ProgramSource.MANUAL, start, null, null, List.of(phase), null, null, null));

        List<ScheduledWorkout> sessions = scheduleService.activate("u1", created.programId());

        ScheduledWorkout normal = sessions.stream().filter(s -> !s.isDeload()).findFirst().orElseThrow();
        ScheduledWorkout deload = sessions.stream().filter(ScheduledWorkout::isDeload).findFirst().orElseThrow();
        // Normal week: untouched (warmup 2, main 4 and 1).
        assertEquals(4, normal.session().blocks().get(1).prescriptions().get(0).sets());
        // Deload week: main 4→2, the 1-set accessory clamps to 1, warm-up untouched.
        assertEquals(2, deload.session().blocks().get(1).prescriptions().get(0).sets());
        assertEquals(1, deload.session().blocks().get(1).prescriptions().get(1).sets());
        assertEquals(2, deload.session().blocks().get(0).prescriptions().get(0).sets());
    }

    @Test
    void reactivatePreservesCompletedSessionsAndRewritesOnlyFuturePlanned() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of())));
        ProgramPhase phase = new ProgramPhase(null, "Accumulation", "Hypertrophy", 0, null,
            4, null, null, null, null, List.of(mon));
        // Started two weeks ago: week 1 is in the past, weeks 3-4 are ahead.
        LocalDate start = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .minusWeeks(2);
        WorkoutProgram input = new WorkoutProgram("u1", null, "Test", null, null, ProgramStatus.DRAFT,
            ProgramSource.MANUAL, start, null, null, List.of(phase), null, null, null);
        WorkoutProgram created = programService.create(input);
        String pid = created.programId();

        // A week-1 session that was performed in the past (manually materialized
        // when the program first started, then logged COMPLETED).
        ScheduledWorkout done = new ScheduledWorkout("u1", pid, start + "_wd_done",
            start, created.phases().get(0).phaseId(), "wd_done", "Lower", 1, false, "home",
            ScheduledStatus.COMPLETED, mon, java.time.Instant.now(), 3600, null);
        scheduled.save(done);

        // First activation materializes the remaining (future) weeks.
        scheduleService.activate("u1", pid);
        long futurePlanned = scheduled.findByProgram("u1", pid, LocalDate.now(), LocalDate.now().plusYears(1))
            .stream().filter(s -> s.status() == ScheduledStatus.PLANNED).count();
        assertTrue(futurePlanned > 0, "expected future PLANNED sessions after activate");

        // Re-activate (what an IMPL-18b edit-commit triggers).
        scheduleService.activate("u1", pid);

        // The completed past session is untouched, and still the only COMPLETED one.
        ScheduledWorkout still = scheduled.findById("u1", pid, start + "_wd_done").orElseThrow();
        assertEquals(ScheduledStatus.COMPLETED, still.status());
        assertEquals(1, scheduleService.completedCount("u1", pid));
        // No PLANNED session was created on a past date (the freeze boundary holds).
        assertTrue(scheduled.findByProgram("u1", pid, LocalDate.MIN, LocalDate.now().minusDays(1))
            .stream().noneMatch(s -> s.status() == ScheduledStatus.PLANNED));
    }

    @Test
    void materializeOneCreatesTodaySessionFromAnyDayAndIsIdempotent() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        // A 4-week program that started five weeks ago — its scheduled window is
        // entirely in the past, so nothing is materialized for today.
        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of())));
        ProgramPhase phase = new ProgramPhase(null, "Accumulation", "Hypertrophy", 0, null,
            4, null, null, null, null, List.of(mon));
        LocalDate start = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .minusWeeks(5);
        WorkoutProgram input = new WorkoutProgram("u1", null, "Test", null, null, ProgramStatus.DRAFT,
            ProgramSource.MANUAL, start, null, null, List.of(phase), null, null, null);
        WorkoutProgram created = programService.create(input);
        String pid = created.programId();
        String phaseId = created.phases().get(0).phaseId();
        String dayId = created.phases().get(0).days().get(0).dayId();

        LocalDate today = LocalDate.now();
        ScheduledWorkout s = scheduleService.materializeOne("u1", pid, phaseId, dayId, today);

        assertEquals(today, s.date());
        assertEquals(today + "_" + dayId, s.scheduledId());
        assertEquals(ScheduledStatus.PLANNED, s.status());
        assertEquals("Lower", s.dayLabel());
        assertTrue(s.session() != null, "the day template rides the materialized session");
        assertTrue(scheduled.findById("u1", pid, today + "_" + dayId).isPresent());

        // Re-running the same day on the same date reuses the row (no duplicate).
        ScheduledWorkout again = scheduleService.materializeOne("u1", pid, phaseId, dayId, today);
        assertEquals(s.scheduledId(), again.scheduledId());
        long count = scheduled.findByProgram("u1", pid, today, today).stream()
            .filter(x -> x.scheduledId().equals(today + "_" + dayId)).count();
        assertEquals(1, count);
    }

    @Test
    void materializeOneReusesExistingSessionWithoutResettingItsOutcome() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of())));
        ProgramPhase phase = new ProgramPhase(null, "Accumulation", "Hypertrophy", 0, null,
            4, null, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now(), null, null, List.of(phase), null, null, null));
        String pid = created.programId();
        String phaseId = created.phases().get(0).phaseId();
        String dayId = created.phases().get(0).days().get(0).dayId();

        LocalDate today = LocalDate.now();
        // A session already run today (COMPLETED) must not be reset to PLANNED.
        ScheduledWorkout done = new ScheduledWorkout("u1", pid, today + "_" + dayId, today,
            phaseId, dayId, "Lower", 1, false, "home",
            ScheduledStatus.COMPLETED, mon, java.time.Instant.now(), 3600, null);
        scheduled.save(done);

        ScheduledWorkout reused = scheduleService.materializeOne("u1", pid, phaseId, dayId, today);
        assertEquals(ScheduledStatus.COMPLETED, reused.status());
    }

    @Test
    void materializeOneResumesLastCompletedLoadButKeepsTemplateVolume() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        // Author template: dumbbell deadlift 4×12–15, no concrete load (null target).
        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(
                new Prescription("dbdl", 0, 4, 12, 15, null, null, 120, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Accumulation", null, 0, null,
            4, 4, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(6), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();
        String dayId = tday.dayId();

        // The last performed session was the DELOAD week: compressed to 2×8–9 at
        // 65 lb, carrying an engine rationale (direction UP).
        PrescriptionRationale rationale = new PrescriptionRationale(
            ProgressionPath.WARMUP, Direction.UP, 5.0, null, null, Confidence.HIGH,
            List.of("last: 60x12", "hit 12 → +5 lb"));
        Prescription doneRx = new Prescription("dbdl", 0, 2, 8, 9, null, null, 120, null, null, null,
            List.of(new LoggedSet(65.0, 8, 1.0, null, java.time.Instant.now())),
            65.0, "double progression", rationale);
        WorkoutDay doneSnapshot = new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(),
            tday.locationId(), tday.orderIndex(),
            List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(doneRx))));
        LocalDate doneDate = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .minusWeeks(1);
        scheduled.save(new ScheduledWorkout("u1", pid, doneDate + "_" + dayId, doneDate,
            phaseId, dayId, "Lower", 4, true, "home",
            ScheduledStatus.COMPLETED, doneSnapshot, java.time.Instant.now(), 3600, null));

        // Redo the day "as today".
        ScheduledWorkout s = scheduleService.materializeOne("u1", pid, phaseId, dayId, LocalDate.now());
        Prescription rx = s.session().blocks().get(0).prescriptions().get(0);

        // Load + basis + rationale resume from the last completed session — NOT the
        // author's null template target (which would otherwise seed a bogus load).
        assertEquals(65.0, rx.targetWeightLbs());
        assertEquals("double progression", rx.loadBasis());
        assertTrue(rx.rationale() != null && rx.rationale().direction() == Direction.UP,
            "the engine rationale rides along so the coach leads with the rep target");
        // ...but rep band + set count stay the TEMPLATE's normal volume, not the
        // deload week's compressed 2×8–9, so a redo never masquerades as a deload.
        assertEquals(4, rx.sets());
        assertEquals(12, rx.repsMin());
        assertEquals(15, rx.repsMax());
        assertTrue(!s.isDeload(), "the redo session itself is never flagged deload");
        // The PLANNED redo carries no logged sets forward.
        assertTrue(rx.loggedSets() == null || rx.loggedSets().isEmpty());
    }

    @Test
    void materializeOneThrowsOnUnknownProgramPhaseOrDay() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of())));
        ProgramPhase phase = new ProgramPhase(null, "Accumulation", "Hypertrophy", 0, null,
            4, null, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now(), null, null, List.of(phase), null, null, null));
        String pid = created.programId();
        String phaseId = created.phases().get(0).phaseId();
        String dayId = created.phases().get(0).days().get(0).dayId();
        LocalDate today = LocalDate.now();

        assertThrows(IllegalArgumentException.class,
            () -> scheduleService.materializeOne("u1", "nope", phaseId, dayId, today));
        assertThrows(IllegalArgumentException.class,
            () -> scheduleService.materializeOne("u1", pid, "nope", dayId, today));
        assertThrows(IllegalArgumentException.class,
            () -> scheduleService.materializeOne("u1", pid, phaseId, "nope", today));
    }

    @Test
    void continueWeekResumesFromLastLoggedLoadNotStartingTemplate() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        // Program's author template: squat 3×5, RPE-based (no concrete load).
        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of(
                new Prescription("squat", 0, 3, 5, 5, null, null, 120, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null,
            5, null, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(6), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();

        // The final performed session: engine had settled on 135 lb (e1RM basis).
        Prescription doneRx = new Prescription("squat", 0, 3, 5, 5, null, null, 120, null, null, null,
            List.of(new LoggedSet(135.0, 5, 1.0, null, java.time.Instant.now())), 135.0, "e1RM", null);
        WorkoutDay doneSnapshot = new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(),
            tday.locationId(), tday.orderIndex(),
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of(doneRx))));
        LocalDate doneDate = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .minusWeeks(1);
        scheduled.save(new ScheduledWorkout("u1", pid, doneDate + "_" + tday.dayId(), doneDate,
            phaseId, tday.dayId(), tday.label(), 5, false, "home",
            ScheduledStatus.COMPLETED, doneSnapshot, java.time.Instant.now(), 3600, null));
        programService.setStatus("u1", pid, ProgramStatus.COMPLETED);

        List<ScheduledWorkout> appended =
            scheduleService.continueProgram("u1", pid, ContinuationScope.WEEK);

        assertEquals(1, appended.size(), "WEEK scope appends one week of the single training day");
        ScheduledWorkout next = appended.get(0);
        assertEquals(ScheduledStatus.PLANNED, next.status());
        assertTrue(!next.date().isBefore(LocalDate.now()), "appended session is not dated in the past");
        Prescription resumed = next.session().blocks().get(0).prescriptions().get(0);
        // Resumes from the last logged load/basis, NOT the null template target.
        assertEquals(135.0, resumed.targetWeightLbs());
        assertEquals("e1RM", resumed.loadBasis());
        assertEquals(3, resumed.sets());
        // A PLANNED session carries no logged sets forward.
        assertTrue(resumed.loggedSets() == null || resumed.loggedSets().isEmpty());
        // Program is training again.
        assertEquals(ProgramStatus.ACTIVE, programs.findById("u1", pid).orElseThrow().status());
    }

    @Test
    void continueCycleRepeatsWholePeriodizationHonoringDeload() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of(
                new Prescription("squat", 0, 4, 5, 5, null, null, 120, null, null, null, null)))));
        // 3-week phase, deload week 3.
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null,
            3, 3, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(4), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        programService.setStatus("u1", pid, ProgramStatus.COMPLETED);

        List<ScheduledWorkout> appended =
            scheduleService.continueProgram("u1", pid, ContinuationScope.CYCLE);

        // 3 weeks × 1 day = 3 sessions; exactly week 3 is deload (halved sets 4→2).
        assertEquals(3, appended.size());
        assertEquals(1, appended.stream().filter(ScheduledWorkout::isDeload).count());
        ScheduledWorkout deload = appended.stream().filter(ScheduledWorkout::isDeload).findFirst().orElseThrow();
        assertEquals(2, deload.session().blocks().get(0).prescriptions().get(0).sets());
    }

    @Test
    void materializeOneSkipsDeloadLoadAndResumesLastWorkingLoad() {
        // #2: "Start this workout today" must use the progression values from the
        // last WORKING day — never the deload week's suppressed load — when a later
        // deload session exists on top of an earlier real training week.
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(
                new Prescription("dbdl", 0, 4, 12, 15, null, null, 120, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Accumulation", null, 0, null,
            4, 4, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(6), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();
        String dayId = tday.dayId();
        LocalDate thisMonday = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

        // Working week (2 weeks ago): engine settled on 85 lb at full 4×12–15.
        Prescription workingRx = new Prescription("dbdl", 0, 4, 12, 15, null, null, 120, null, null, null,
            List.of(new LoggedSet(85.0, 13, 1.0, null, java.time.Instant.now())), 85.0, "double progression", null);
        LocalDate workingDate = thisMonday.minusWeeks(2);
        scheduled.save(new ScheduledWorkout("u1", pid, workingDate + "_" + dayId, workingDate,
            phaseId, dayId, "Lower", 3, false, "home", ScheduledStatus.COMPLETED,
            new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(), tday.locationId(), tday.orderIndex(),
                List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(workingRx)))),
            java.time.Instant.now(), 3600, null));

        // Deload week (last week, the MOST RECENT completed session): suppressed to 65 lb.
        Prescription deloadRx = new Prescription("dbdl", 0, 2, 8, 9, null, null, 120, null, null, null,
            List.of(new LoggedSet(65.0, 8, 1.0, null, java.time.Instant.now())), 65.0, "deload", null);
        LocalDate deloadDate = thisMonday.minusWeeks(1);
        scheduled.save(new ScheduledWorkout("u1", pid, deloadDate + "_" + dayId, deloadDate,
            phaseId, dayId, "Lower", 4, true, "home", ScheduledStatus.COMPLETED,
            new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(), tday.locationId(), tday.orderIndex(),
                List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(deloadRx)))),
            java.time.Instant.now(), 3600, null));

        ScheduledWorkout s = scheduleService.materializeOne("u1", pid, phaseId, dayId, LocalDate.now());
        Prescription rx = s.session().blocks().get(0).prescriptions().get(0);

        assertEquals(85.0, rx.targetWeightLbs(), "resumes the last working load, not the deload's 65");
        assertEquals("double progression", rx.loadBasis());
        assertEquals(4, rx.sets());
    }

    @Test
    void continuationResumesDemonstratedLoadWhenStoredPrescriptionIsStale() {
        // Prod bug (2026-10-06): the athlete's newest COMPLETED session was minted
        // same-day from the author template (stored rx 160 × 12–15, rationale-less)
        // but they actually lifted 180 × 8 on every set. The engine's derived next
        // prescription had nowhere to land (no future sessions existed), so the
        // continuation copied the stale stored 160 forward. The demonstrated top
        // set must win, and the band must widen to admit the demonstrated reps.
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Press", 0, List.of(
                new Prescription("legpress", 0, 4, 12, 15, null, null, 90, null, null, null, null,
                    160.0, "Matches your recent 160x15 performance.")))));
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null,
            4, null, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(6), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();
        String dayId = tday.dayId();

        // Newest completed session: stored rx is the stale template (160 × 12–15),
        // logged sets show the athlete actually did 180 × 8 on all four sets.
        Prescription doneRx = new Prescription("legpress", 0, 4, 12, 15, null, null, 90, null, null, null,
            List.of(
                new LoggedSet(180.0, 8, null, null, java.time.Instant.now()),
                new LoggedSet(180.0, 8, null, null, java.time.Instant.now()),
                new LoggedSet(180.0, 8, null, null, java.time.Instant.now()),
                new LoggedSet(180.0, 8, null, null, java.time.Instant.now())),
            160.0, "Matches your recent 160x15 performance.", null);
        LocalDate doneDate = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .minusWeeks(1);
        scheduled.save(new ScheduledWorkout("u1", pid, doneDate + "_" + dayId, doneDate,
            phaseId, dayId, "Lower", 4, false, "home", ScheduledStatus.COMPLETED,
            new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(), tday.locationId(), tday.orderIndex(),
                List.of(new Block(null, BlockType.MAIN, "Press", 0, List.of(doneRx)))),
            java.time.Instant.now(), 3600, null));
        programService.setStatus("u1", pid, ProgramStatus.COMPLETED);

        List<ScheduledWorkout> appended =
            scheduleService.continueProgram("u1", pid, ContinuationScope.WEEK);
        Prescription resumed = appended.get(0).session().blocks().get(0).prescriptions().get(0);

        assertEquals(180.0, resumed.targetWeightLbs(),
            "demonstrated 180×8 beats the stale stored 160 template target");
        assertEquals(8, resumed.repsMin(), "band widens down to the demonstrated reps");
        assertEquals(15, resumed.repsMax());
        assertTrue(resumed.loadBasis() != null && resumed.loadBasis().contains("180"),
            "load basis names the demonstrated set, was: " + resumed.loadBasis());
    }

    @Test
    void demonstratedLoadFillsInWhenStoredTargetWasNeverStamped() {
        // Prod bug sibling: an exercise whose stored prescriptions NEVER carried a
        // target (the engine's writeback had nowhere to land) kept resuming null —
        // so the response assembler re-seeded a "first-time lift" guess (the
        // 47.5 lb dumbbell deadlift) despite weeks of real 75 lb logs.
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(
                new Prescription("dbdl", 0, 4, 10, 12, null, null, 90, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null,
            4, null, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(6), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();
        String dayId = tday.dayId();

        // Newest completed: stored target null (template never stamped), logged 75×8.
        Prescription doneRx = new Prescription("dbdl", 0, 4, 10, 12, null, null, 90, null, null, null,
            List.of(new LoggedSet(75.0, 8, null, null, java.time.Instant.now())),
            null, null, null);
        LocalDate doneDate = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .minusWeeks(1);
        scheduled.save(new ScheduledWorkout("u1", pid, doneDate + "_" + dayId, doneDate,
            phaseId, dayId, "Lower", 4, false, "home", ScheduledStatus.COMPLETED,
            new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(), tday.locationId(), tday.orderIndex(),
                List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(doneRx)))),
            java.time.Instant.now(), 3600, null));

        // Both rails resume the demonstrated 75 WITH its reps: the ad-hoc mint...
        ScheduledWorkout minted = scheduleService.materializeOne("u1", pid, phaseId, dayId, LocalDate.now());
        Prescription mintedRx = minted.session().blocks().get(0).prescriptions().get(0);
        assertEquals(75.0, mintedRx.targetWeightLbs(),
            "mint resumes the demonstrated load instead of leaving null (which re-seeds a guess)");
        assertEquals(4, mintedRx.sets());
        assertEquals(8, mintedRx.repsMin(),
            "reps travel WITH the weight — the band admits the 8 reps the 75 was lifted for");
        assertEquals(12, mintedRx.repsMax());

        // ...and the continuation.
        programService.setStatus("u1", pid, ProgramStatus.COMPLETED);
        List<ScheduledWorkout> appended =
            scheduleService.continueProgram("u1", pid, ContinuationScope.WEEK);
        Prescription resumed = appended.get(0).session().blocks().get(0).prescriptions().get(0);
        assertEquals(75.0, resumed.targetWeightLbs());
        assertEquals(8, resumed.repsMin(), "continuation band admits the demonstrated 8 reps");
    }

    @Test
    void materializeOneResumesEngineBandTogetherWithItsLoad() {
        // "Run today" must resume weight AND the reps it was prescribed at as a
        // unit: an engine-stamped 170 × 8–9 must not come back as 170 × the
        // template's 12–15 band.
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Press", 0, List.of(
                new Prescription("legpress", 0, 4, 12, 15, null, null, 90, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null,
            4, null, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(6), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();
        String dayId = tday.dayId();

        // Newest working session: engine-stamped 170 at a tightened 8–9 band,
        // performed exactly as prescribed (demo does not beat the stored target).
        PrescriptionRationale rationale = new PrescriptionRationale(
            ProgressionPath.WARMUP, Direction.UP, 10.0, null, null, Confidence.HIGH,
            List.of("last: 160×15,15,15,15", "hit 12 on all sets → +10 lb"));
        Prescription doneRx = new Prescription("legpress", 0, 4, 8, 9, null, null, 90, null, null, null,
            List.of(new LoggedSet(170.0, 8, null, null, java.time.Instant.now())),
            170.0, "double progression", rationale);
        LocalDate doneDate = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .minusWeeks(1);
        scheduled.save(new ScheduledWorkout("u1", pid, doneDate + "_" + dayId, doneDate,
            phaseId, dayId, "Lower", 3, false, "home", ScheduledStatus.COMPLETED,
            new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(), tday.locationId(), tday.orderIndex(),
                List.of(new Block(null, BlockType.MAIN, "Press", 0, List.of(doneRx)))),
            java.time.Instant.now(), 3600, null));

        ScheduledWorkout minted = scheduleService.materializeOne("u1", pid, phaseId, dayId, LocalDate.now());
        Prescription rx = minted.session().blocks().get(0).prescriptions().get(0);
        assertEquals(170.0, rx.targetWeightLbs());
        assertEquals(8, rx.repsMin(), "the band the 170 was prescribed at travels with it");
        assertEquals(9, rx.repsMax());
        assertEquals(4, rx.sets());
        assertTrue(rx.rationale() != null && rx.rationale().direction() == Direction.UP);
    }

    @Test
    void nullTargetWorkingPrescriptionNoLongerShadowsDeloadFallback() {
        // The newest working session's rx carried no target (never stamped), while
        // an older deload session's rx did. The old putIfAbsent let the null-target
        // working rx claim the slot, so the resume carried nothing at all.
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(
                new Prescription("dbdl", 0, 4, 10, 12, null, null, 90, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null,
            4, 4, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(6), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();
        String dayId = tday.dayId();
        LocalDate thisMonday = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

        // Older deload session: rx DOES carry a target (65) — and no logged sets.
        Prescription deloadRx = new Prescription("dbdl", 0, 2, 8, 9, null, null, 90, null, null, null,
            null, 65.0, "double progression", null);
        LocalDate deloadDate = thisMonday.minusWeeks(2);
        scheduled.save(new ScheduledWorkout("u1", pid, deloadDate + "_" + dayId, deloadDate,
            phaseId, dayId, "Lower", 4, true, "home", ScheduledStatus.COMPLETED,
            new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(), tday.locationId(), tday.orderIndex(),
                List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(deloadRx)))),
            java.time.Instant.now(), 3600, null));

        // Newest working session: null target, and no logged weights either.
        Prescription workingRx = new Prescription("dbdl", 0, 4, 10, 12, null, null, 90, null, null, null,
            null, null, null, null);
        LocalDate workingDate = thisMonday.minusWeeks(1);
        scheduled.save(new ScheduledWorkout("u1", pid, workingDate + "_" + dayId, workingDate,
            phaseId, dayId, "Lower", 5, false, "home", ScheduledStatus.COMPLETED,
            new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(), tday.locationId(), tday.orderIndex(),
                List.of(new Block(null, BlockType.MAIN, "Hinge", 0, List.of(workingRx)))),
            java.time.Instant.now(), 3600, null));

        ScheduledWorkout minted = scheduleService.materializeOne("u1", pid, phaseId, dayId, LocalDate.now());
        Prescription rx = minted.session().blocks().get(0).prescriptions().get(0);
        assertEquals(65.0, rx.targetWeightLbs(),
            "the deload rx's real target is the last resort — better than resuming nothing");
        assertEquals(4, rx.sets(), "volume stays the template's, not the deload's 2×8–9");
        assertEquals(10, rx.repsMin());
    }

    @Test
    void ensureUpcomingAppendsNextCycleWhenProgramRanOut() {
        // #1: a still-followed program with no remaining future sessions is extended
        // in place so the athlete always has a next workout (lazy auto-continue).
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of(
                new Prescription("squat", 0, 4, 5, 5, null, null, 120, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null, 3, 3, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(5), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();
        programService.setStatus("u1", pid, ProgramStatus.ACTIVE);

        Prescription doneRx = new Prescription("squat", 0, 4, 5, 5, null, null, 120, null, null, null,
            List.of(new LoggedSet(150.0, 5, 1.0, null, java.time.Instant.now())), 150.0, "e1RM", null);
        LocalDate doneDate = LocalDate.now()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(1);
        scheduled.save(new ScheduledWorkout("u1", pid, doneDate + "_" + tday.dayId(), doneDate,
            phaseId, tday.dayId(), tday.label(), 3, false, "home", ScheduledStatus.COMPLETED,
            new WorkoutDay(tday.dayId(), tday.label(), tday.dayOfWeek(), tday.locationId(), tday.orderIndex(),
                List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of(doneRx)))),
            java.time.Instant.now(), 3600, null));

        List<ScheduledWorkout> upcoming = scheduleService.ensureUpcoming("u1", pid, LocalDate.now());

        assertEquals(3, upcoming.size(), "CYCLE append = 3 weeks × 1 day of future PLANNED work");
        assertTrue(upcoming.stream().allMatch(s -> s.status() == ScheduledStatus.PLANNED));
        assertTrue(upcoming.stream().allMatch(s -> !s.date().isBefore(LocalDate.now())));
        assertEquals(150.0, upcoming.get(0).session().blocks().get(0).prescriptions().get(0).targetWeightLbs(),
            "resumes the last working load, not the author's week-one template");
        assertEquals(ProgramStatus.ACTIVE, programs.findById("u1", pid).orElseThrow().status());
    }

    @Test
    void ensureUpcomingLeavesFutureWorkAndArchivedProgramsUntouched() {
        FakeProgramRepo programs = new FakeProgramRepo();
        FakeScheduledRepo scheduled = new FakeScheduledRepo();
        WorkoutProgramService programService = new WorkoutProgramService(programs);
        WorkoutScheduleService scheduleService = new WorkoutScheduleService(programs, scheduled, programService);

        WorkoutDay mon = new WorkoutDay(null, "Lower", DayOfWeek.MON, "home", 0,
            List.of(new Block(null, BlockType.MAIN, "Squat", 0, List.of(
                new Prescription("squat", 0, 4, 5, 5, null, null, 120, null, null, null, null)))));
        ProgramPhase phase = new ProgramPhase(null, "Block", null, 0, null, 3, null, null, null, null, List.of(mon));
        WorkoutProgram created = programService.create(new WorkoutProgram("u1", null, "Test", null, null,
            ProgramStatus.DRAFT, ProgramSource.MANUAL, LocalDate.now().minusWeeks(5), null, null,
            List.of(phase), null, null, null));
        String pid = created.programId();
        WorkoutDay tday = created.phases().get(0).days().get(0);
        String phaseId = created.phases().get(0).phaseId();

        // A future PLANNED session already exists → ensureUpcoming is a no-op.
        LocalDate future = LocalDate.now().plusDays(3);
        scheduled.save(new ScheduledWorkout("u1", pid, future + "_" + tday.dayId(), future,
            phaseId, tday.dayId(), tday.label(), 1, false, "home", ScheduledStatus.PLANNED,
            tday, null, null, null));
        programService.setStatus("u1", pid, ProgramStatus.ACTIVE);

        List<ScheduledWorkout> upcoming = scheduleService.ensureUpcoming("u1", pid, LocalDate.now());
        assertEquals(1, upcoming.size());
        assertEquals(1, scheduleService.calendar("u1", pid, LocalDate.MIN, LocalDate.MAX).size(),
            "no new sessions appended when future work already exists");

        // Archived with no future work → still left untouched (deliberately shelved).
        scheduled.deletePlannedFrom("u1", pid, LocalDate.MIN);
        programService.setStatus("u1", pid, ProgramStatus.ARCHIVED);
        List<ScheduledWorkout> archived = scheduleService.ensureUpcoming("u1", pid, LocalDate.now());
        assertTrue(archived.isEmpty(), "an archived program is not auto-resurrected");
        assertEquals(ProgramStatus.ARCHIVED, programs.findById("u1", pid).orElseThrow().status());
    }

    static class FakeProgramRepo implements WorkoutProgramRepository {
        final Map<String, WorkoutProgram> store = new ConcurrentHashMap<>();
        @Override public Optional<WorkoutProgram> findById(String userId, String programId) {
            return Optional.ofNullable(store.get(userId + "/" + programId));
        }
        @Override public List<WorkoutProgram> findByUser(String userId) { return List.copyOf(store.values()); }
        @Override public List<WorkoutProgram> findByUserIncludingArchived(String userId) { return List.copyOf(store.values()); }
        @Override public void save(WorkoutProgram p) { store.put(p.userId() + "/" + p.programId(), p); }
        @Override public void delete(String userId, String programId) { store.remove(userId + "/" + programId); }
    }

    static class FakeScheduledRepo implements ScheduledWorkoutRepository {
        final Map<String, ScheduledWorkout> store = new ConcurrentHashMap<>();
        @Override public List<ScheduledWorkout> findByProgram(String userId, String programId, LocalDate from, LocalDate to) {
            return store.values().stream()
                .filter(s -> userId.equals(s.userId()) && programId.equals(s.programId()))
                .filter(s -> !s.date().isBefore(from) && !s.date().isAfter(to))
                .toList();
        }
        @Override public void save(ScheduledWorkout s) { store.put(s.scheduledId(), s); }
        @Override public void deletePlannedFrom(String userId, String programId, LocalDate from) {
            store.values().removeIf(s -> userId.equals(s.userId()) && programId.equals(s.programId())
                && s.status() == ScheduledStatus.PLANNED && !s.date().isBefore(from));
        }
    }
}
