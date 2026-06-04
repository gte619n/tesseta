package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutScheduleService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    // Wide bounds so the date-range query returns every session.
    private static final LocalDate MIN = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX = LocalDate.of(2999, 12, 31);

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

    @GetMapping
    public List<ScheduledWorkoutResponse> history() {
        String userId = currentUser.get().userId();
        List<ScheduledWorkout> completed = new ArrayList<>();
        for (WorkoutProgram p : programs.list(userId)) {
            for (ScheduledWorkout sw : schedule.calendar(userId, p.programId(), MIN, MAX)) {
                if (sw.status() == ScheduledStatus.COMPLETED) {
                    completed.add(sw);
                }
            }
        }
        // Newest first; fall back to the session date when no finish time exists.
        completed.sort(Comparator.comparing(
            WorkoutHistoryController::performedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return assembler.scheduled(userId, completed);
    }

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
