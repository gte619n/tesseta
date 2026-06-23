package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.ExercisePerformanceDigestService;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramValidator;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutScheduleService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.InvalidSessionLogException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/me/workout-programs")
public class WorkoutProgramController {

    private final CurrentUserProvider currentUser;
    private final WorkoutProgramService service;
    private final WorkoutScheduleService schedule;
    private final WorkoutSessionCompletionService completion;
    private final WorkoutProgramValidator validator;
    private final WorkoutProgramAssembler assembler;
    private final WorkoutSessionCoach coach;
    private final ExercisePerformanceDigestService digests;
    private final SyncChangeNotifier syncNotifier;

    public WorkoutProgramController(
        CurrentUserProvider currentUser,
        WorkoutProgramService service,
        WorkoutScheduleService schedule,
        WorkoutSessionCompletionService completion,
        WorkoutProgramValidator validator,
        WorkoutProgramAssembler assembler,
        WorkoutSessionCoach coach,
        ExercisePerformanceDigestService digests,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.service = service;
        this.schedule = schedule;
        this.completion = completion;
        this.validator = validator;
        this.assembler = assembler;
        this.coach = coach;
        this.digests = digests;
        this.syncNotifier = syncNotifier;
    }

    @GetMapping
    public List<WorkoutProgramResponse> list() {
        String userId = currentUser.get().userId();
        return service.list(userId).stream().map(WorkoutProgramResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<WorkoutProgramDeepResponse> create(@RequestBody CreateProgramRequest body) {
        String userId = currentUser.get().userId();
        WorkoutProgram input = new WorkoutProgram(
            userId, null, body.title(), body.description(), body.goalId(),
            ProgramStatus.DRAFT, body.source(), body.startDate(), body.schedule(),
            null, body.phases(), null, null, null);
        WorkoutProgram created = service.create(input);
        syncNotifier.changed(userId, null, "workoutPrograms");
        return ResponseEntity.status(HttpStatus.CREATED).body(assembler.deep(created));
    }

    @GetMapping("/{programId}")
    public WorkoutProgramDeepResponse getDeep(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        WorkoutProgram p = service.findById(userId, programId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // When a phase has no template days (e.g. imported history), render its
        // days from the performed sessions. Only pay for that read when needed.
        boolean needsSessions = p.phases() != null
            && p.phases().stream().anyMatch(ph -> ph.days() == null || ph.days().isEmpty());
        List<ScheduledWorkout> sessions = needsSessions
            ? schedule.calendar(userId, programId, LocalDate.of(1970, 1, 1), LocalDate.of(2999, 12, 31))
            : List.of();
        return assembler.deep(p, sessions);
    }

    @PatchMapping("/{programId}")
    public WorkoutProgramDeepResponse update(@PathVariable String programId, @RequestBody UpdateProgramRequest body) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        WorkoutProgram updatedProgram = service.update(userId, programId, body.title(), body.description(),
            body.goalId(), body.schedule(), body.startDate(), body.status(), body.phases());
        syncNotifier.changed(userId, null, "workoutPrograms");
        return assembler.deep(updatedProgram);
    }

    @DeleteMapping("/{programId}")
    public ResponseEntity<Void> archive(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.archive(userId, programId);
        syncNotifier.changed(userId, null, "workoutPrograms");
        return ResponseEntity.noContent().build();
    }

    /** Validate without persisting changes — returns the inline issue list. */
    @PostMapping("/{programId}/validate")
    public List<String> validate(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        WorkoutProgram p = service.findById(userId, programId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return validator.validate(userId, p);
    }

    @PostMapping("/{programId}/activate")
    public ResponseEntity<?> activate(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        WorkoutProgram p = service.findById(userId, programId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<String> issues = validator.validate(userId, p);
        if (!issues.isEmpty()) {
            // Return the flat issue list as { issues: [...] } — the same shape as
            // the designer commit 422 — so clients can surface the specific,
            // actionable problems inline rather than a generic failure (IMPL-STAB G1).
            return ResponseEntity.unprocessableEntity().body(java.util.Map.of("issues", issues));
        }
        List<ScheduledWorkout> scheduled = schedule.activate(userId, programId);
        syncNotifier.changed(userId, null, "workoutPrograms", "workoutPrograms/scheduled");
        return ResponseEntity.ok(assembler.scheduled(userId, scheduled));
    }

    /**
     * ADR-0012 completion upsert: record a session's outcome (COMPLETED or
     * SKIPPED) with full per-set actuals. Idempotent — outbox retries and
     * after-the-fact edits replay the same PUT and re-run the fan-out.
     */
    @PutMapping("/{programId}/sessions/{scheduledId}")
    public ScheduledWorkoutResponse logSession(
        @PathVariable String programId,
        @PathVariable String scheduledId,
        @RequestBody LogSessionRequest body
    ) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        ScheduledWorkout updated;
        try {
            updated = completion.complete(userId, programId, scheduledId,
                body.status(), body.completedAt(), body.durationSeconds(), body.logged());
        } catch (IllegalArgumentException e) {
            // The core service signals a missing session this way (no Spring Web there).
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (InvalidSessionLogException e) {
            // Flat issue list (same shape as the validator) so clients flag fields inline.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        syncNotifier.changed(userId, null, "workoutPrograms/scheduled");
        // IMPL-COACH: attach a best-effort AI recap to the completion response
        // (transient — not persisted, null when the coach is unavailable).
        ScheduledWorkoutResponse response = assembler.scheduled(userId, List.of(updated)).get(0);
        return response.withAiRecap(coach.recapFor(response));
    }

    @GetMapping("/{programId}/calendar")
    public List<ScheduledWorkoutResponse> calendar(
        @PathVariable String programId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return assembler.scheduled(userId, schedule.calendar(userId, programId, from, to));
    }

    /**
     * IMPL-COACH: best-effort AI recap for a completed session, fetched
     * separately because the phone's completion upsert is offline-first (the
     * outbox replays the PUT asynchronously, so the recap can't ride its
     * response). Returns {@code {recap: null}} when the session isn't completed
     * yet or the coach is unavailable — never an error, so the client just
     * shows the numeric summary.
     */
    @GetMapping("/{programId}/sessions/{scheduledId}/recap")
    public SessionRecapResponse sessionRecap(
        @PathVariable String programId,
        @PathVariable String scheduledId
    ) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return schedule.session(userId, programId, scheduledId)
            .map(sw -> assembler.scheduled(userId, List.of(sw)).get(0))
            .map(response -> new SessionRecapResponse(coach.recapFor(response)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * IMPL-COACH PR2: the sets performed the last time each of this session's
     * exercises was done, keyed by exerciseId. The live coach prefills new sets
     * from these — the literal "previous time you did this" — falling back to
     * the designed target when an exercise has no history. Best-effort: an
     * exercise absent from the map simply has no prior data.
     */
    @GetMapping("/{programId}/sessions/{scheduledId}/last-sets")
    public Map<String, List<LastSetView>> sessionLastSets(
        @PathVariable String programId,
        @PathVariable String scheduledId
    ) {
        String userId = currentUser.get().userId();
        ScheduledWorkout sw = schedule.session(userId, programId, scheduledId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        Set<String> exerciseIds = new LinkedHashSet<>();
        if (sw.session() != null && sw.session().blocks() != null) {
            for (Block block : sw.session().blocks()) {
                if (block.prescriptions() == null) continue;
                for (Prescription rx : block.prescriptions()) {
                    if (rx.exerciseId() != null) exerciseIds.add(rx.exerciseId());
                }
            }
        }

        Map<String, List<LoggedSet>> last = digests.lastSessionSets(userId, exerciseIds);
        Map<String, List<LastSetView>> out = new LinkedHashMap<>();
        last.forEach((id, sets) -> out.put(id, sets.stream().map(LastSetView::from).toList()));
        return out;
    }
}
