package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
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
