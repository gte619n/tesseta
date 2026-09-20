package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.api.support.RequestTimeZone;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.api.workoutprogram.LastSetView;
import com.gte619n.healthfitness.api.workoutprogram.ScheduledWorkoutResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramAssembler;
import com.gte619n.healthfitness.core.adhoc.AdHocSessionService;
import com.gte619n.healthfitness.core.adhoc.AdHocSource;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutService;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.ExercisePerformanceDigestService;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.InvalidSessionLogException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * Ad-hoc workout library (IMPL-ADHOC-01). Full CRUD + archival on reusable
 * templates, plus the terminal run-log upsert. Generation lives on
 * {@link AdHocGenerationController}.
 */
@RestController
@RequestMapping("/api/me/adhoc-workouts")
public class AdHocWorkoutController {

    private final CurrentUserProvider currentUser;
    private final AdHocWorkoutService service;
    private final AdHocSessionService runs;
    private final WorkoutProgramAssembler assembler;
    private final ExercisePerformanceDigestService digests;
    private final SyncChangeNotifier syncNotifier;
    private final SyncWriteContext syncWrite;

    public AdHocWorkoutController(
        CurrentUserProvider currentUser,
        AdHocWorkoutService service,
        AdHocSessionService runs,
        WorkoutProgramAssembler assembler,
        ExercisePerformanceDigestService digests,
        SyncChangeNotifier syncNotifier,
        SyncWriteContext syncWrite
    ) {
        this.currentUser = currentUser;
        this.service = service;
        this.runs = runs;
        this.assembler = assembler;
        this.digests = digests;
        this.syncNotifier = syncNotifier;
        this.syncWrite = syncWrite;
    }

    /** Library list with filter (tag / text / max-duration) + sort (D7). */
    @GetMapping
    public List<AdHocWorkoutSummaryResponse> list(
        @RequestParam(required = false) Boolean includeArchived,
        @RequestParam(required = false) String tag,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Integer maxDurationMin,
        @RequestParam(required = false, defaultValue = "recent") String sort,
        @RequestParam(required = false, defaultValue = "true") boolean pinnedFirst
    ) {
        String userId = currentUser.get().userId();
        List<AdHocWorkout> all = Boolean.TRUE.equals(includeArchived)
            ? service.listIncludingArchived(userId)
            : service.list(userId);

        String needle = q == null ? null : q.trim().toLowerCase();
        List<AdHocWorkout> filtered = all.stream()
            .filter(w -> tag == null || (w.tags() != null && w.tags().stream()
                .anyMatch(t -> t.equalsIgnoreCase(tag))))
            .filter(w -> needle == null || needle.isBlank()
                || (w.title() != null && w.title().toLowerCase().contains(needle))
                || (w.summary() != null && w.summary().toLowerCase().contains(needle)))
            .filter(w -> maxDurationMin == null || w.estimatedDurationSeconds() == null
                || w.estimatedDurationSeconds() <= maxDurationMin * 60)
            .sorted(comparator(sort, pinnedFirst))
            .toList();

        return filtered.stream().map(AdHocWorkoutSummaryResponse::from).toList();
    }

    private static Comparator<AdHocWorkout> comparator(String sort, boolean pinnedFirst) {
        Comparator<AdHocWorkout> base = switch (sort == null ? "recent" : sort) {
            case "mostDone" -> Comparator.comparingInt(AdHocWorkout::runCount).reversed();
            case "created" -> Comparator.comparing(AdHocWorkout::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed();
            default -> Comparator.comparing(
                (AdHocWorkout w) -> w.lastPerformedAt() != null ? w.lastPerformedAt() : w.updatedAt(),
                Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        };
        if (pinnedFirst) {
            return Comparator.comparing((AdHocWorkout w) -> !w.pinned()).thenComparing(base);
        }
        return base;
    }

    @PostMapping
    public ResponseEntity<AdHocWorkoutResponse> create(@RequestBody SaveAdHocRequest body) {
        String userId = currentUser.get().userId();
        // Idempotent on the Idempotency-Key header: the id is server-minted, so an
        // outbox replay would otherwise create a duplicate template.
        AdHocWorkoutResponse response = syncWrite.idempotentCreate(
            "adhocWorkouts:create",
            userId,
            () -> {
                AdHocWorkout input = new AdHocWorkout(
                    userId, null, body.title(), body.summary(),
                    body.source() != null ? body.source() : AdHocSource.MANUAL,
                    body.prompt(),
                    body.equipmentContext() == null ? null : body.equipmentContext().toDomain(),
                    body.targetDurationMinutes(), null, body.tags(), body.pinned(),
                    body.day(), 0, null, null, null);
                AdHocWorkout created = service.create(input);
                syncNotifier.changed(userId, null, "adhocWorkouts");
                return new SyncWriteContext.Created<>(created.adhocId(), toResponse(userId, created));
            },
            adhocId -> service.findById(userId, adhocId).map(w -> toResponse(userId, w)));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{adhocId}")
    public AdHocWorkoutResponse getOne(@PathVariable String adhocId) {
        String userId = currentUser.get().userId();
        AdHocWorkout w = service.findById(userId, adhocId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return toResponse(userId, w);
    }

    @PatchMapping("/{adhocId}")
    public AdHocWorkoutResponse update(@PathVariable String adhocId, @RequestBody UpdateAdHocRequest body) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, adhocId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Any manual edit downgrades AI_GENERATED → AI_ASSISTED (D3): the workout
        // is no longer purely as-generated once the user touches the body.
        AdHocWorkout existing = service.findById(userId, adhocId).get();
        AdHocSource newSource = body.day() != null && existing.source() == AdHocSource.AI_GENERATED
            ? AdHocSource.AI_ASSISTED : null;
        AdHocWorkout updated = service.update(userId, adhocId, body.title(), body.summary(),
            body.tags(), body.pinned(), body.day(), body.targetDurationMinutes(), newSource);
        syncNotifier.changed(userId, null, "adhocWorkouts");
        return toResponse(userId, updated);
    }

    @DeleteMapping("/{adhocId}")
    public ResponseEntity<Void> archive(@PathVariable String adhocId) {
        String userId = currentUser.get().userId();
        if (service.findById(userId, adhocId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.archive(userId, adhocId);
        syncNotifier.changed(userId, null, "adhocWorkouts");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{adhocId}/restore")
    public AdHocWorkoutResponse restore(@PathVariable String adhocId) {
        String userId = currentUser.get().userId();
        service.restore(userId, adhocId);
        syncNotifier.changed(userId, null, "adhocWorkouts");
        return service.findById(userId, adhocId)
            .map(w -> toResponse(userId, w))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Terminal run upsert (AD-05): materialize the snapshot from the template on
     * first arrival + record the outcome. Idempotent under outbox replay.
     */
    @PutMapping("/{adhocId}/sessions/{sessionId}")
    public ScheduledWorkoutResponse logRun(
        @PathVariable String adhocId,
        @PathVariable String sessionId,
        @RequestBody LogAdHocSessionRequest body,
        @RequestHeader(value = RequestTimeZone.HEADER, required = false) String timezone
    ) {
        String userId = currentUser.get().userId();
        LocalDate date = body.date() != null ? body.date() : LocalDate.now(RequestTimeZone.resolve(timezone));
        ScheduledWorkout updated;
        try {
            updated = runs.complete(userId, adhocId, sessionId, body.status(), date,
                body.completedAt(), body.durationSeconds(), body.logged(), body.feeling());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (InvalidSessionLogException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        syncNotifier.changed(userId, null, "adhocWorkouts", "adhocWorkouts/sessions");
        return assembler.scheduled(userId, List.of(updated)).get(0);
    }

    /** Prior-performance prefill for a run's exercises (D6) — online-only. */
    @PostMapping("/{adhocId}/last-sets")
    public Map<String, List<LastSetView>> lastSets(
        @PathVariable String adhocId,
        @RequestBody(required = false) com.gte619n.healthfitness.api.workoutprogram.LastSetsRequest body
    ) {
        String userId = currentUser.get().userId();
        Set<String> exerciseIds = new LinkedHashSet<>();
        if (body != null && body.exerciseIds() != null) {
            for (String id : body.exerciseIds()) {
                if (id != null && !id.isBlank()) exerciseIds.add(id);
            }
        } else {
            // Fall back to the template's own exercises when the client sends none.
            service.findById(userId, adhocId).ifPresent(w -> exerciseIds.addAll(exerciseIdsOf(w.day())));
        }
        Map<String, List<LoggedSet>> last = digests.lastSessionSets(userId, exerciseIds);
        Map<String, List<LastSetView>> out = new LinkedHashMap<>();
        last.forEach((id, sets) -> out.put(id, sets.stream().map(LastSetView::from).toList()));
        return out;
    }

    private static Set<String> exerciseIdsOf(WorkoutDay day) {
        Set<String> ids = new LinkedHashSet<>();
        if (day == null || day.blocks() == null) return ids;
        for (Block b : day.blocks()) {
            if (b.prescriptions() == null) continue;
            for (Prescription rx : b.prescriptions()) {
                if (rx.exerciseId() != null) ids.add(rx.exerciseId());
            }
        }
        return ids;
    }

    private AdHocWorkoutResponse toResponse(String userId, AdHocWorkout w) {
        return AdHocWorkoutResponse.from(w, assembler.day(userId, w.day()));
    }
}
