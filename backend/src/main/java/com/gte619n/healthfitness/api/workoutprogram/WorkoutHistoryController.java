package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutScheduleService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workout History: the user's performed sessions across every program, newest
 * first. Programs hold the plan (phases → days → exercises/sets/reps); this is
 * the record of what was actually done — date, finish time, elapsed duration,
 * and the logged sets. Backed by the same COMPLETED {@link ScheduledWorkout}s
 * the program view and calendar read.
 */
@RestController
@RequestMapping("/api/me/workout-history")
public class WorkoutHistoryController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentUserProvider currentUser;
    private final WorkoutProgramService programs;
    private final WorkoutScheduleService schedule;
    private final WorkoutProgramAssembler assembler;

    public WorkoutHistoryController(
        CurrentUserProvider currentUser,
        WorkoutProgramService programs,
        WorkoutScheduleService schedule,
        WorkoutProgramAssembler assembler
    ) {
        this.currentUser = currentUser;
        this.programs = programs;
        this.schedule = schedule;
        this.assembler = assembler;
    }

    /**
     * One page of performed sessions, newest first, across every program. Page
     * size defaults to {@value #DEFAULT_PAGE_SIZE}. Only the requested page's
     * sessions are assembled into full responses (the rest stay as lightweight
     * records), and each row carries its program/phase titles so the client can
     * draw delineation headers between phases.
     */
    @GetMapping
    public WorkoutHistoryPageResponse history(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size
    ) {
        String userId = currentUser.get().userId();
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        // Keep the owning programs so the assembler can resolve program/phase titles.
        Map<String, WorkoutProgram> programsById = new LinkedHashMap<>();
        List<ScheduledWorkout> completed = new ArrayList<>();
        for (WorkoutProgram p : programs.list(userId)) {
            programsById.put(p.programId(), p);
            completed.addAll(schedule.completedSessions(userId, p.programId()));
        }
        // Newest first; fall back to the session date when no finish time exists.
        completed.sort(Comparator.comparing(
            WorkoutHistoryController::performedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        int total = completed.size();
        int from = Math.min(pageIndex * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<ScheduledWorkout> slice = completed.subList(from, to);
        List<ScheduledWorkoutResponse> items = assembler.scheduled(userId, slice, programsById);
        return new WorkoutHistoryPageResponse(items, pageIndex, pageSize, total, to < total);
    }

    /** A page of history rows plus the cursor metadata the client pages with. */
    public record WorkoutHistoryPageResponse(
        List<ScheduledWorkoutResponse> items, int page, int size, int total, boolean hasMore) {}

    /** Cheap header counts for the Workouts hub — no full session reads. */
    @GetMapping("/summary")
    public WorkoutHistorySummaryResponse summary() {
        String userId = currentUser.get().userId();
        int count = 0;
        LocalDate last = null;
        for (WorkoutProgram p : programs.list(userId)) {
            count += schedule.completedCount(userId, p.programId());
            LocalDate d = schedule.lastCompletedDate(userId, p.programId()).orElse(null);
            if (d != null && (last == null || d.isAfter(last))) {
                last = d;
            }
        }
        return new WorkoutHistorySummaryResponse(count, last);
    }

    public record WorkoutHistorySummaryResponse(int count, LocalDate lastWorkoutDate) {}

    private static Instant performedAt(ScheduledWorkout sw) {
        if (sw.completedAt() != null) return sw.completedAt();
        return sw.date() == null ? null : sw.date().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
