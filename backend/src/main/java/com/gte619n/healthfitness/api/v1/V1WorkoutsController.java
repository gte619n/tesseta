package com.gte619n.healthfitness.api.v1;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The training-schedule surface (ADR-0020). Requires workouts:read.
//   GET /v1/programs                       — programs (schedule), paginated
//   GET /v1/programs/{programId}           — one program, full phase tree
//   GET /v1/workouts                       — completed sessions (history)
//   GET /v1/workouts/{programId}/{sched}   — one completed session + logged sets
@RestController
@PreAuthorize("hasAuthority('SCOPE_workouts:read')")
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class V1WorkoutsController {

    private final CurrentUserProvider currentUser;
    private final WorkoutProgramRepository programs;
    private final ScheduledWorkoutRepository scheduled;

    public V1WorkoutsController(
        CurrentUserProvider currentUser,
        WorkoutProgramRepository programs,
        ScheduledWorkoutRepository scheduled
    ) {
        this.currentUser = currentUser;
        this.programs = programs;
        this.scheduled = scheduled;
    }

    @GetMapping("/v1/programs")
    public V1Page<ProgramSummary> listPrograms(
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String updatedSince
    ) {
        String userId = currentUser.get().userId();
        Instant since = V1Params.instant(updatedSince);
        List<WorkoutProgram> all = programs.findByUser(userId).stream()
            .filter(p -> since == null || (p.updatedAt() != null && !p.updatedAt().isBefore(since)))
            .toList();
        return V1Page.paginate(all, WorkoutProgram::updatedAt, WorkoutProgram::programId,
            V1WorkoutsController::toSummary, cursor, V1Params.limit(limit));
    }

    @GetMapping("/v1/programs/{programId}")
    public ProgramDetail getProgram(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        WorkoutProgram program = programs.findById(userId, programId)
            .orElseThrow(() -> new NoSuchElementException("program not found"));
        return toDetail(program);
    }

    @GetMapping("/v1/workouts")
    public V1Page<WorkoutSummary> listWorkouts(
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to
    ) {
        String userId = currentUser.get().userId();
        LocalDate fromDate = V1Params.date(from);
        LocalDate toDate = V1Params.date(to);
        List<ScheduledWorkout> completed = new ArrayList<>();
        for (WorkoutProgram program : programs.findByUser(userId)) {
            for (ScheduledWorkout s : scheduled.findByStatus(userId, program.programId(),
                ScheduledStatus.COMPLETED)) {
                if (fromDate != null && s.date().isBefore(fromDate)) continue;
                if (toDate != null && s.date().isAfter(toDate)) continue;
                completed.add(s);
            }
        }
        return V1Page.paginate(completed, V1WorkoutsController::sessionSortKey,
            ScheduledWorkout::scheduledId, V1WorkoutsController::toWorkoutSummary,
            cursor, V1Params.limit(limit));
    }

    @GetMapping("/v1/workouts/{programId}/{scheduledId}")
    public WorkoutDetail getWorkout(
        @PathVariable String programId, @PathVariable String scheduledId) {
        String userId = currentUser.get().userId();
        ScheduledWorkout session = scheduled.findById(userId, programId, scheduledId)
            .orElseThrow(() -> new NoSuchElementException("workout session not found"));
        return toWorkoutDetail(session);
    }

    // --- sort keys ---

    private static Instant sessionSortKey(ScheduledWorkout s) {
        if (s.completedAt() != null) return s.completedAt();
        return s.date() == null ? Instant.EPOCH : s.date().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // --- mappers ---

    private static ProgramSummary toSummary(WorkoutProgram p) {
        return new ProgramSummary(
            p.programId(), p.title(), p.description(), p.goalId(),
            name(p.status()), name(p.source()), p.startDate(),
            p.phases() == null ? 0 : p.phases().size(),
            p.createdAt(), p.updatedAt(), p.completedAt());
    }

    private static ProgramDetail toDetail(WorkoutProgram p) {
        List<PhaseDto> phases = p.phases() == null ? List.of()
            : p.phases().stream().map(V1WorkoutsController::toPhase).toList();
        return new ProgramDetail(
            p.programId(), p.title(), p.description(), p.goalId(),
            name(p.status()), name(p.source()), p.startDate(),
            p.phaseOrder() == null ? List.of() : p.phaseOrder(),
            phases, p.createdAt(), p.updatedAt(), p.completedAt());
    }

    private static PhaseDto toPhase(ProgramPhase phase) {
        List<DayDto> days = phase.days() == null ? List.of()
            : phase.days().stream().map(V1WorkoutsController::toDay).toList();
        return new PhaseDto(phase.phaseId(), phase.title(), phase.focus(), phase.orderIndex(),
            name(phase.status()), phase.weeks(), phase.deloadWeekIndex(),
            phase.targetStartDate(), phase.targetEndDate(), phase.completedAt(), days);
    }

    private static DayDto toDay(WorkoutDay day) {
        List<BlockDto> blocks = day.blocks() == null ? List.of()
            : day.blocks().stream().map(V1WorkoutsController::toBlock).toList();
        return new DayDto(day.dayId(), day.label(), name(day.dayOfWeek()),
            day.locationId(), day.orderIndex(), blocks);
    }

    private static BlockDto toBlock(Block block) {
        List<PrescriptionDto> ps = block.prescriptions() == null ? List.of()
            : block.prescriptions().stream().map(V1WorkoutsController::toPrescription).toList();
        return new BlockDto(block.blockId(), name(block.type()), block.title(),
            block.orderIndex(), ps);
    }

    private static PrescriptionDto toPrescription(Prescription p) {
        return new PrescriptionDto(
            p.exerciseId(), p.orderIndex(), p.sets(), p.repsMin(), p.repsMax(),
            p.durationSeconds(), p.restSeconds(), p.tempo(), p.notes(),
            p.targetWeightLbs(), p.loadBasis());
    }

    private static WorkoutSummary toWorkoutSummary(ScheduledWorkout s) {
        return new WorkoutSummary(
            s.programId(), s.scheduledId(), s.date(), s.phaseId(), s.dayId(), s.dayLabel(),
            s.weekIndexInPhase(), s.isDeload(), name(s.status()),
            s.completedAt(), s.durationSeconds());
    }

    private static WorkoutDetail toWorkoutDetail(ScheduledWorkout s) {
        List<LoggedExercise> exercises = new ArrayList<>();
        WorkoutDay session = s.session();
        if (session != null && session.blocks() != null) {
            for (Block block : session.blocks()) {
                if (block.prescriptions() == null) continue;
                for (Prescription p : block.prescriptions()) {
                    List<LoggedSetDto> sets = p.loggedSets() == null ? List.of()
                        : p.loggedSets().stream().map(V1WorkoutsController::toLoggedSet).toList();
                    exercises.add(new LoggedExercise(
                        p.exerciseId(), name(block.type()), p.notes(), sets));
                }
            }
        }
        return new WorkoutDetail(
            s.programId(), s.scheduledId(), s.date(), s.phaseId(), s.dayId(), s.dayLabel(),
            s.weekIndexInPhase(), s.isDeload(), name(s.status()),
            s.completedAt(), s.durationSeconds(), exercises);
    }

    private static LoggedSetDto toLoggedSet(LoggedSet l) {
        return new LoggedSetDto(l.weightLbs(), l.reps(), l.rpe(), l.restSeconds(),
            l.completedAt(), l.durationSeconds());
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    // --- DTOs (frozen v1 wire shapes, decoupled from core entities) ---

    public record ProgramSummary(
        String id, String title, String description, String goalId,
        String status, String source, LocalDate startDate, int phaseCount,
        Instant createdAt, Instant updatedAt, Instant completedAt) {}

    public record ProgramDetail(
        String id, String title, String description, String goalId,
        String status, String source, LocalDate startDate, List<String> phaseOrder,
        List<PhaseDto> phases, Instant createdAt, Instant updatedAt, Instant completedAt) {}

    public record PhaseDto(
        String id, String title, String focus, int orderIndex, String status,
        int weeks, Integer deloadWeekIndex, LocalDate targetStartDate, LocalDate targetEndDate,
        Instant completedAt, List<DayDto> days) {}

    public record DayDto(
        String id, String label, String dayOfWeek, String locationId,
        int orderIndex, List<BlockDto> blocks) {}

    public record BlockDto(
        String id, String type, String title, int orderIndex, List<PrescriptionDto> prescriptions) {}

    public record PrescriptionDto(
        String exerciseId, int orderIndex, Integer sets, Integer repsMin, Integer repsMax,
        Integer durationSeconds, Integer restSeconds, String tempo, String notes,
        Double targetWeightLbs, String loadBasis) {}

    public record WorkoutSummary(
        String programId, String scheduledId, LocalDate date, String phaseId, String dayId,
        String dayLabel, int weekIndexInPhase, boolean isDeload, String status,
        Instant completedAt, Integer durationSeconds) {}

    public record WorkoutDetail(
        String programId, String scheduledId, LocalDate date, String phaseId, String dayId,
        String dayLabel, int weekIndexInPhase, boolean isDeload, String status,
        Instant completedAt, Integer durationSeconds, List<LoggedExercise> exercises) {}

    public record LoggedExercise(
        String exerciseId, String blockType, String notes, List<LoggedSetDto> loggedSets) {}

    public record LoggedSetDto(
        Double weightLbs, Integer reps, Double rpe, Integer restSeconds,
        Instant completedAt, Integer durationSeconds) {}
}
