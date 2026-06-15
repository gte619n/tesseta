package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.config.SseEvents;
import com.gte619n.healthfitness.config.JsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.config.SseStreamer;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.goals.chat.ChatRole;
import com.gte619n.healthfitness.core.goals.chat.UserHealthSnapshotService;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.trt.TrtAdvisorContextService;
import com.gte619n.healthfitness.core.workoutprogram.ExerciseDigest;
import com.gte619n.healthfitness.core.workoutprogram.ExercisePerformanceDigestService;
import com.gte619n.healthfitness.core.workoutprogram.ExerciseSetLog;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.TrainingScienceScaffold;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramValidator;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutScheduleService;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatMessage;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatRepository;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatThread;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutProgramChatClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Workout-program designer chat. The user fills a FORM (training days + gym per
 * day + optional goal) that opens a persistent thread; that schedule is fixed
 * for the thread and drives the strictly per-gym exercise allow-lists fed to
 * the model. {@code POST /chat} streams tokens + a validated {@code proposal};
 * {@code POST /chat/{threadId}/commit} persists the (edited) program. Threads
 * and messages are persisted like the Goals chat.
 */
@RestController
@RequestMapping("/api/me/workout-programs/chat")
public class WorkoutProgramChatController {

    private static final Logger log = LoggerFactory.getLogger(WorkoutProgramChatController.class);
    private static final long SSE_TIMEOUT_MS = 180_000L;
    private static final ObjectMapper JSON = JsonSupport.WEB;

    private final CurrentUserProvider currentUser;
    private final WorkoutProgramChatClient chatClient;
    private final WorkoutProgramChatRepository chat;
    private final UserHealthSnapshotService snapshots;
    private final ExerciseAvailabilityService availability;
    private final ExerciseService exercises;
    private final ExercisePerformanceDigestService digests;
    private final TrainingScienceScaffold science;
    private final TrtAdvisorContextService trt;
    private final WorkoutProgramService service;
    private final WorkoutScheduleService scheduleService;
    private final WorkoutProgramValidator validator;
    private final WorkoutProgramAssembler assembler;
    private final SseStreamer sseStreamer;

    public WorkoutProgramChatController(
        CurrentUserProvider currentUser,
        WorkoutProgramChatClient chatClient,
        WorkoutProgramChatRepository chat,
        UserHealthSnapshotService snapshots,
        ExerciseAvailabilityService availability,
        ExerciseService exercises,
        ExercisePerformanceDigestService digests,
        TrainingScienceScaffold science,
        TrtAdvisorContextService trt,
        WorkoutProgramService service,
        WorkoutScheduleService scheduleService,
        WorkoutProgramValidator validator,
        WorkoutProgramAssembler assembler,
        SseStreamer sseStreamer
    ) {
        this.currentUser = currentUser;
        this.chatClient = chatClient;
        this.chat = chat;
        this.snapshots = snapshots;
        this.availability = availability;
        this.exercises = exercises;
        this.digests = digests;
        this.science = science;
        this.trt = trt;
        this.service = service;
        this.scheduleService = scheduleService;
        this.validator = validator;
        this.assembler = assembler;
        this.sseStreamer = sseStreamer;
    }

    /**
     * The pre-chat form selections, sent on the FIRST message to open a thread.
     * {@code programId} (IMPL-18b) opens the chat in edit mode against an
     * already-active program; when set, the schedule/goal are seeded from that
     * program and the form schedule is ignored.
     */
    public record ChatRequest(String threadId, String message, ProgramSchedule schedule,
                              String goalId, String programId) {}
    public record ThreadResponse(String threadId, String title, ProgramSchedule schedule, String goalId,
                                 String programId, Instant createdAt, Instant updatedAt) {
        static ThreadResponse from(WorkoutProgramChatThread t) {
            return new ThreadResponse(t.threadId(), t.title(), t.schedule(), t.goalId(),
                t.programId(), t.createdAt(), t.updatedAt());
        }
    }
    public record MessageResponse(String messageId, String role, String content, String proposalJson, Instant createdAt) {
        static MessageResponse from(WorkoutProgramChatMessage m) {
            return new MessageResponse(m.messageId(), m.role() == null ? null : m.role().name(),
                m.content(), m.proposalJson(), m.createdAt());
        }
    }

    @GetMapping("/threads")
    public List<ThreadResponse> threads() {
        String userId = currentUser.get().userId();
        return chat.listThreads(userId).stream().map(ThreadResponse::from).toList();
    }

    @GetMapping("/{threadId}")
    public List<MessageResponse> messages(@PathVariable String threadId) {
        String userId = currentUser.get().userId();
        if (chat.findThread(userId, threadId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat thread not found");
        }
        return chat.listMessages(userId, threadId).stream().map(MessageResponse::from).toList();
    }

    /**
     * The user's TRT monitoring panel for the designer's labs surface
     * (ADR-0015): current markers vs range with trend, plus any danger flags.
     */
    @GetMapping("/trt-context")
    public com.gte619n.healthfitness.core.trt.TrtContext trtContext() {
        String userId = currentUser.get().userId();
        return trt.build(userId);
    }

    @DeleteMapping("/threads/{threadId}")
    public ResponseEntity<Void> deleteThread(@PathVariable String threadId) {
        String userId = currentUser.get().userId();
        if (chat.findThread(userId, threadId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat thread not found");
        }
        chat.deleteThread(userId, threadId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest body) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        final String userId = currentUser.get().userId();
        final String message = body.message();

        // Resolve or create the thread synchronously so a bad threadId surfaces
        // as 4xx before the stream opens. The schedule/goal come from the form
        // on the first message and are fixed for the thread.
        final WorkoutProgramChatThread thread;
        if (body.threadId() != null && !body.threadId().isBlank()) {
            thread = chat.findThread(userId, body.threadId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat thread not found"));
        } else if (body.programId() != null && !body.programId().isBlank()) {
            // IMPL-18b edit mode: bind the thread to an existing active program;
            // the schedule + goal come from that program (the form is ignored).
            WorkoutProgram existing = service.findById(userId, body.programId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Program not found"));
            String threadId = UUID.randomUUID().toString();
            WorkoutProgramChatThread created = new WorkoutProgramChatThread(
                userId, threadId, deriveTitle(message), existing.schedule(), existing.goalId(),
                null, null, existing.programId());
            chat.createThread(created);
            thread = created;
        } else {
            if (body.schedule() == null || body.schedule().dayLocations() == null
                || body.schedule().dayLocations().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A schedule (training days + a gym per day) is required to start a program chat");
            }
            String threadId = UUID.randomUUID().toString();
            WorkoutProgramChatThread created = new WorkoutProgramChatThread(
                userId, threadId, deriveTitle(message), body.schedule(), body.goalId(), null, null);
            chat.createThread(created);
            thread = created;
        }

        final List<WorkoutProgramChatClient.Turn> history = new ArrayList<>();
        for (WorkoutProgramChatMessage m : chat.listMessages(userId, thread.threadId())) {
            history.add(new WorkoutProgramChatClient.Turn(m.role() == ChatRole.USER, m.content()));
        }
        chat.appendMessage(userId, new WorkoutProgramChatMessage(
            thread.threadId(), UUID.randomUUID().toString(), ChatRole.USER, message, null, null));

        final String context = buildContext(userId, thread);
        final ProgramSchedule schedule = thread.schedule();
        final String goalId = thread.goalId();
        final String threadId = thread.threadId();
        final String editProgramId = thread.programId();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseStreamer.stream(() -> {
            StringBuilder assistantText = new StringBuilder();
            try {
                WorkoutProgramChatClient.StreamResult result = chatClient.streamChat(
                    history, message, context, token -> {
                        assistantText.append(token);
                        SseEvents.send(emitter, JSON, "token", Map.of("text", token));
                    }, toolResolver(userId));

                String proposalJson = null;
                if (result.proposal() != null) {
                    WorkoutProgram p = result.proposal();
                    // Stamp userId + the form's schedule/goal so the proposal
                    // is grounded in the user's chosen gyms. In edit mode
                    // (IMPL-18b) carry the existing programId/status/startDate so
                    // the preview reflects the program being revised in place and
                    // the anchored timeline is preserved.
                    WorkoutProgram existing = editProgramId == null ? null
                        : service.findById(userId, editProgramId).orElse(null);
                    WorkoutProgram withUser = new WorkoutProgram(
                        userId,
                        existing != null ? existing.programId() : null,
                        p.title(), p.description(), goalId,
                        existing != null ? existing.status() : ProgramStatus.DRAFT,
                        p.source(),
                        existing != null ? existing.startDate() : p.startDate(),
                        schedule, p.phaseOrder(), p.phases(), null, null, null,
                        p.nutritionGuidance());
                    List<String> issues = validator.validate(userId, withUser);
                    List<String> warnings = validator.warnings(userId, withUser);
                    WorkoutProgramDeepResponse deep = assembler.deep(withUser);
                    Map<String, Object> payload = Map.of(
                        "program", deep, "issues", issues, "warnings", warnings);
                    proposalJson = JSON.writeValueAsString(payload);
                    SseEvents.send(emitter, JSON, "proposal", payload);
                }

                chat.appendMessage(userId, new WorkoutProgramChatMessage(
                    threadId, UUID.randomUUID().toString(), ChatRole.ASSISTANT,
                    result.assistantText() != null ? result.assistantText() : assistantText.toString(),
                    proposalJson, null));

                SseEvents.send(emitter, JSON, "done", Map.of("threadId", threadId));
                emitter.complete();
            } catch (Exception e) {
                log.error("Workout-program chat failed for user {} (thread {}): {}",
                    userId, threadId, e.toString(), e);
                SseEvents.send(emitter, JSON, "error", Map.of("error", e.getMessage() == null ? "Chat failed" : e.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    @PostMapping("/{threadId}/commit")
    public ResponseEntity<?> commit(@PathVariable String threadId, @RequestBody CreateProgramRequest body) {
        String userId = currentUser.get().userId();
        WorkoutProgramChatThread thread = chat.findThread(userId, threadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat thread not found"));
        // The schedule/goal are fixed by the form on the thread; the body may
        // override title/description/phases (the user's edits).
        ProgramSchedule schedule = body.schedule() != null ? body.schedule() : thread.schedule();
        String goalId = body.goalId() != null ? body.goalId() : thread.goalId();
        ProgramSource source = body.source() != null ? body.source() : ProgramSource.AI_ASSISTED;

        // IMPL-18b: a program-bound thread edits that program IN PLACE and
        // re-materializes its FORWARD schedule, never rewriting completed
        // sessions. A normal thread creates a new draft.
        if (thread.programId() != null) {
            WorkoutProgram existing = service.findById(userId, thread.programId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Program not found"));
            // Validate the edited tree under the existing program's identity
            // (status + startDate kept so the timeline anchor is preserved).
            WorkoutProgram candidate = new WorkoutProgram(
                userId, existing.programId(), body.title(), body.description(), goalId,
                existing.status(), existing.source(), existing.startDate(), schedule,
                null, body.phases(), existing.createdAt(), null, existing.completedAt(),
                body.nutritionGuidance());
            List<String> issues = validator.validate(userId, candidate);
            if (!issues.isEmpty()) {
                return ResponseEntity.unprocessableEntity().body(Map.of("issues", issues));
            }
            // startDate=null keeps the anchor; status=null keeps ACTIVE/COMPLETED.
            service.update(userId, existing.programId(), body.title(), body.description(),
                goalId, schedule, null, null, body.phases());
            scheduleService.activate(userId, existing.programId());
            WorkoutProgram updated = service.findById(userId, existing.programId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Program not found"));
            return ResponseEntity.ok(assembler.deep(updated));
        }

        WorkoutProgram input = new WorkoutProgram(
            userId, null, body.title(), body.description(), goalId, ProgramStatus.DRAFT,
            source, body.startDate(), schedule, null, body.phases(), null, null, null,
            body.nutritionGuidance());
        List<String> issues = validator.validate(userId, input);
        if (!issues.isEmpty()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("issues", issues));
        }
        WorkoutProgram created = service.create(input);
        return ResponseEntity.status(HttpStatus.CREATED).body(assembler.deep(created));
    }

    /** Health snapshot + STRICTLY per-gym allow-lists, scoped to the form's gyms. */
    private String buildContext(String userId, WorkoutProgramChatThread thread) {
        ProgramSchedule schedule = thread.schedule();
        StringBuilder sb = new StringBuilder();
        // IMPL-18b: when editing an active program, lead with what it currently
        // is and which weeks are frozen, so the model revises forward in place.
        if (thread.programId() != null) {
            sb.append(renderExistingProgram(userId, thread.programId())).append("\n\n");
        }
        try {
            String snapshot = snapshots.buildSnapshot(userId);
            if (snapshot != null && !snapshot.isBlank()) {
                sb.append("USER HEALTH SNAPSHOT:\n").append(snapshot).append("\n\n");
            }
        } catch (Exception e) {
            log.warn("Health snapshot unavailable for {}: {}", userId, e.getMessage());
        }

        Map<DayOfWeek, String> dayLocations = schedule == null || schedule.dayLocations() == null
            ? Map.of() : schedule.dayLocations();
        sb.append("TRAINING SCHEDULE (design exactly these days at these gyms):\n");
        dayLocations.forEach((day, locId) ->
            sb.append("  ").append(day).append(" -> gym locationId=").append(locId).append('\n'));

        sb.append("\nEXECUTABLE EXERCISES PER GYM (use ONLY these exerciseIds, and "
            + "only at the matching locationId):\n");
        Set<String> gyms = new LinkedHashSet<>(dayLocations.values());
        Map<String, List<Exercise>> executableByGym = availability.executableAt(userId, gyms);
        for (String locId : gyms) {
            sb.append("- locationId=").append(locId).append(":\n");
            List<Exercise> executable = executableByGym.getOrDefault(locId, List.of());
            if (executable.isEmpty()) {
                sb.append("    (no published exercises are executable here)\n");
            }
            for (Exercise e : executable) {
                sb.append("    ").append(e.exerciseId()).append(" — ").append(e.name())
                    .append(" ").append(e.suitableBlockTypes()).append('\n');
            }
        }

        // IMPL-18: history digest for the candidate exercises, the science
        // scaffold, and the (ADR-0015) TRT context.
        sb.append('\n').append(renderDigest(userId, executableByGym));
        sb.append('\n').append(science.render());
        try {
            String trtContext = trt.renderForPrompt(userId);
            if (trtContext != null && !trtContext.isBlank()) {
                sb.append('\n').append(trtContext);
            }
        } catch (Exception e) {
            log.warn("TRT context unavailable for {}: {}", userId, e.getMessage());
        }
        return sb.toString();
    }

    /**
     * IMPL-18b edit-mode context: the program the user is revising, its current
     * phase/day/exercise structure, and the FREEZE boundary — weeks before today
     * are completed and immutable, so the model must only change things from
     * today forward and keep elapsed weeks intact (the timeline stays anchored to
     * the original start date).
     */
    private String renderExistingProgram(String userId, String programId) {
        WorkoutProgram p = service.findById(userId, programId).orElse(null);
        if (p == null) {
            return "";
        }
        LocalDate today = LocalDate.now();
        LocalDate start = p.startDate();
        StringBuilder sb = new StringBuilder();
        sb.append("EDITING AN ACTIVE PROGRAM (revise it in place — do NOT start over):\n");
        sb.append("  title: ").append(p.title()).append('\n');
        if (start != null) {
            long weeksIn = Math.max(0, java.time.temporal.ChronoUnit.WEEKS.between(start, today));
            sb.append("  started: ").append(start)
              .append(" (today is ").append(today)
              .append(", ~week ").append(weeksIn + 1).append(" of the program)\n");
        }
        int done = scheduleService.completedCount(userId, programId);
        sb.append("  completed sessions so far: ").append(done);
        scheduleService.lastCompletedDate(userId, programId)
            .ifPresent(d -> sb.append(" (most recent ").append(d).append(')'));
        sb.append('\n');
        sb.append("  FREEZE RULE: every session dated before ").append(today)
          .append(" is DONE and immutable. Only propose changes from ").append(today)
          .append(" onward; keep already-elapsed weeks/phases unchanged so dates stay anchored.\n");
        sb.append("  CURRENT STRUCTURE:\n");
        for (ProgramPhase phase : p.phases()) {
            sb.append("  - phase \"").append(phase.title()).append("\" — ")
              .append(phase.weeks()).append(" wk");
            if (phase.deloadWeekIndex() != null) sb.append(", deload wk ").append(phase.deloadWeekIndex());
            sb.append('\n');
            for (WorkoutDay day : phase.days()) {
                sb.append("      ").append(day.dayOfWeek()).append(' ').append(day.label())
                  .append(" @ ").append(day.locationId()).append(": ");
                List<String> items = new ArrayList<>();
                for (Block b : day.blocks()) {
                    for (Prescription rx : b.prescriptions()) {
                        StringBuilder one = new StringBuilder(rx.exerciseId());
                        if (rx.sets() != null) {
                            one.append(' ').append(rx.sets()).append('x');
                            if (rx.repsMin() != null) one.append(rx.repsMin());
                            if (rx.repsMax() != null && !rx.repsMax().equals(rx.repsMin())) {
                                one.append('-').append(rx.repsMax());
                            }
                        }
                        if (rx.targetWeightLbs() != null) {
                            one.append(" @").append(Math.round(rx.targetWeightLbs())).append("lb");
                        }
                        items.add(one.toString());
                    }
                }
                sb.append(String.join(", ", items)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Compact per-exercise performance digest for the prompt (IMPL-18 S2/R3):
     * the exercises the user has actually trained, capped to the most recently
     * performed ~20 to bound tokens. The model drills deeper via
     * {@code get_exercise_history}.
     */
    private String renderDigest(String userId, Map<String, List<Exercise>> executableByGym) {
        Map<String, ExerciseDigest> all;
        try {
            all = digests.digestAll(userId);
        } catch (Exception e) {
            log.warn("Exercise digest unavailable for {}: {}", userId, e.getMessage());
            return "";
        }
        if (all.isEmpty()) {
            return "EXERCISE PERFORMANCE DIGEST: no logged history yet — prescribe by RPE/%1RM.\n";
        }
        Map<String, String> names = new java.util.HashMap<>();
        executableByGym.values().forEach(list ->
            list.forEach(e -> names.put(e.exerciseId(), e.name())));
        List<ExerciseDigest> top = all.values().stream()
            .sorted(Comparator.comparing(
                ExerciseDigest::lastPerformed, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(20)
            .toList();
        // Fill any missing names in one batch.
        List<String> missing = top.stream().map(ExerciseDigest::exerciseId)
            .filter(id -> !names.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            for (Exercise e : exercises.findByIds(missing)) names.put(e.exerciseId(), e.name());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("EXERCISE PERFORMANCE DIGEST (what the user has actually lifted — "
            + "use to set concrete loads, discounted for staleness/ease-in):\n");
        for (ExerciseDigest d : top) {
            String name = names.getOrDefault(d.exerciseId(), d.exerciseId());
            sb.append("  ").append(d.exerciseId()).append(" (").append(name).append("): ");
            if (d.estimated1Rm() != null) {
                sb.append("e1RM ~").append(Math.round(d.estimated1Rm())).append(" lb");
                if (d.lowConfidence()) sb.append(" (low-confidence, weight-only)");
            } else {
                sb.append("no usable 1RM");
            }
            if (d.bestRecentWeightLbs() != null) {
                sb.append(", best ").append(Math.round(d.bestRecentWeightLbs())).append(" lb");
                if (d.bestRecentReps() != null) sb.append("x").append(d.bestRecentReps());
            }
            if (d.weeksSinceLast() != null) sb.append(", last ").append(d.weeksSinceLast()).append("w ago");
            if (d.typicalRpe() != null) sb.append(", typ RPE ").append(d.typicalRpe());
            if (d.trailing4wkSets() != null) {
                sb.append(", ").append(d.trailing4wkSets()).append(" sets/4wk");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Binds the model's read-only data tools to the current user's services. */
    private WorkoutProgramChatClient.ToolResolver toolResolver(String userId) {
        return (toolName, args) -> {
            if (GeminiToolNames.EXERCISE_HISTORY.equals(toolName)) {
                String exerciseId = asString(args.get("exerciseId"));
                String exerciseName = asString(args.get("exerciseName"));
                int limit = asInt(args.get("limit"), 5);
                if (exerciseId == null && exerciseName != null) {
                    exerciseId = exercises.listPublished(exerciseName, null, null, null).stream()
                        .findFirst().map(Exercise::exerciseId).orElse(null);
                }
                if (exerciseId == null) {
                    return Map.of("error", "no matching exercise", "sets", List.of());
                }
                List<Map<String, Object>> sets = new ArrayList<>();
                for (ExerciseSetLog s : digests.history(userId, exerciseId, limit)) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("date", s.date() == null ? null : s.date().toString());
                    m.put("weightLbs", s.weightLbs());
                    m.put("reps", s.reps());
                    m.put("rpe", s.rpe());
                    sets.add(m);
                }
                return Map.of("exerciseId", exerciseId, "sets", sets);
            }
            if (GeminiToolNames.LAB_HISTORY.equals(toolName)) {
                String marker = asString(args.get("markerName"));
                if (marker == null) return Map.of("error", "markerName required", "points", List.of());
                List<Map<String, Object>> points = new ArrayList<>();
                trt.markerHistory(userId, marker).forEach(p -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("date", p.date() == null ? null : p.date().toString());
                    m.put("value", p.value());
                    m.put("unit", p.unit());
                    m.put("refLow", p.refLow());
                    m.put("refHigh", p.refHigh());
                    points.add(m);
                });
                return Map.of("marker", marker, "points", points);
            }
            return Map.of("error", "unknown tool: " + toolName);
        };
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return def;
        try { return (int) Math.round(Double.parseDouble(o.toString())); } catch (NumberFormatException e) { return def; }
    }

    /** Tool names mirrored from the Gemini client so the resolver can dispatch. */
    private static final class GeminiToolNames {
        static final String EXERCISE_HISTORY = "get_exercise_history";
        static final String LAB_HISTORY = "get_lab_history";
    }

    private static String deriveTitle(String message) {
        String trimmed = message.strip();
        String[] words = trimmed.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, words.length); i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        if (words.length > 6) sb.append('…');
        return sb.isEmpty() ? "Program chat" : sb.toString();
    }
}
