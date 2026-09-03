package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.api.nutrition.MacrosDto;
import com.gte619n.healthfitness.api.support.RequestTimeZone;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.ExercisePerformanceDigestService;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final WorkoutProgramNutritionService programNutrition;
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
        WorkoutProgramNutritionService programNutrition,
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
        this.programNutrition = programNutrition;
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
     * Materialize an ad-hoc session for one program day on a target date
     * (default: today), so any workout can be started and logged "as today" —
     * even after the program's scheduled window has elapsed or a day was missed.
     * Idempotent by the {@code "{date}_{dayId}"} id: re-running the same day on
     * the same date returns the existing session.
     *
     * <p>"Today" is the caller's <em>local</em> day, derived from the
     * {@code X-Timezone} header — the server clock is UTC in production, so
     * defaulting to it would date an evening workout to tomorrow for users
     * behind UTC. An explicit {@code body.date()} still wins.
     */
    @PostMapping("/{programId}/sessions")
    public ScheduledWorkoutResponse runDay(
        @PathVariable String programId,
        @RequestBody RunDayRequest body,
        @RequestHeader(value = RequestTimeZone.HEADER, required = false) String timezone
    ) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        LocalDate date = body.date() != null
            ? body.date()
            : LocalDate.now(RequestTimeZone.resolve(timezone));
        ScheduledWorkout created;
        try {
            created = schedule.materializeOne(userId, programId, body.phaseId(), body.dayId(), date);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        syncNotifier.changed(userId, null, "workoutPrograms/scheduled");
        return assembler.scheduled(userId, List.of(created)).get(0);
    }

    /**
     * ADR-0012 completion upsert: record a session's outcome (COMPLETED or
     * SKIPPED) with full per-set actuals. Idempotent — outbox retries and
     * after-the-fact edits replay the same PUT and re-run the fan-out.
     *
     * <p>Offline-first run-as-today: an ad-hoc session is minted client-side
     * (shared {@code "{date}_{dayId}"} id) and this PUT is the first the server
     * hears of it. When the body carries the day reference
     * ({@code phaseId}/{@code dayId}/{@code date}) and the session was never
     * materialized, it is materialized here before the outcome lands — the
     * create+complete arrive as one idempotent, outbox-replayable call.
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
        materializeIfMissing(userId, programId, scheduledId, body);
        ScheduledWorkout updated;
        try {
            updated = completion.complete(userId, programId, scheduledId,
                body.status(), body.completedAt(), body.durationSeconds(), body.logged(), body.feeling());
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

    /**
     * Materialize a client-minted ad-hoc session (offline-first run-as-today)
     * the first time its completion PUT arrives. A no-op when the day reference
     * is absent or the session already exists; a reference that contradicts the
     * shared {@code "{date}_{dayId}"} id convention is a 400 (materializing it
     * would create a row the PUT then couldn't find).
     */
    private void materializeIfMissing(
        String userId, String programId, String scheduledId, LogSessionRequest body
    ) {
        if (body.phaseId() == null || body.dayId() == null || body.date() == null) {
            return;
        }
        if (schedule.session(userId, programId, scheduledId).isPresent()) {
            return;
        }
        String expectedId = body.date() + "_" + body.dayId();
        if (!expectedId.equals(scheduledId)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "scheduledId must be \"{date}_{dayId}\" (" + expectedId + "), got: " + scheduledId
            );
        }
        try {
            schedule.materializeOne(userId, programId, body.phaseId(), body.dayId(), body.date());
        } catch (IllegalArgumentException e) {
            // Unknown program/phase/day — same contract as the run-day POST.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
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

    /**
     * Resilient sibling of {@link #sessionLastSets}: the client passes the
     * exerciseIds it already holds in its local session draft, so the "same as
     * last time" prefill resolves even before the current session exists
     * server-side. The GET-by-scheduledId variant {@code orElseThrow(404)}s
     * until the session is persisted — which for an offline-first / ad-hoc
     * session run past the program's materialized schedule is only at completion
     * — so a brand-new session's prefill would silently blank out. Strictly
     * user-scoped history (only the caller's own logged sets, for the ids they
     * name); the path {@code programId} is for route consistency, not a filter.
     */
    @PostMapping("/{programId}/last-sets")
    public Map<String, List<LastSetView>> lastSetsForExercises(
        @PathVariable String programId,
        @RequestBody LastSetsRequest body
    ) {
        String userId = currentUser.get().userId();
        Set<String> exerciseIds = new LinkedHashSet<>();
        if (body != null && body.exerciseIds() != null) {
            for (String id : body.exerciseIds()) {
                if (id != null && !id.isBlank()) exerciseIds.add(id);
            }
        }
        Map<String, List<LoggedSet>> last = digests.lastSessionSets(userId, exerciseIds);
        Map<String, List<LastSetView>> out = new LinkedHashMap<>();
        last.forEach((id, sets) -> out.put(id, sets.stream().map(LastSetView::from).toList()));
        return out;
    }

    /**
     * The program's effective nutrition guidance (active phase's, else
     * program-level), used to show/enable an "Apply as nutrition target" action
     * and preview the macros. 204 when the program carries no guidance OR the
     * user's current target already matches it (applying would be a no-op).
     */
    @GetMapping("/{programId}/nutrition-guidance")
    public ResponseEntity<NutritionGuidance> nutritionGuidance(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return programNutrition.guidanceToApply(userId, programId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Apply this program's nutrition guidance as the user's macro target
     * (calories re-derived from the macros). 409 when the program has no guidance.
     */
    @PostMapping("/{programId}/nutrition-target")
    public MacrosDto applyNutritionTarget(@PathVariable String programId) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, programId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Macros applied = programNutrition.applyToTarget(userId, programId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This program has no nutrition guidance to apply."));
        syncNotifier.changed(userId, null, "nutritionTargets");
        return MacrosDto.from(applied);
    }
}
