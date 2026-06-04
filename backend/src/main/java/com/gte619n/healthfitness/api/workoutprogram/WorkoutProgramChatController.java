package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.config.SseEvents;
import com.gte619n.healthfitness.config.JsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.config.SseStreamer;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.goals.chat.ChatRole;
import com.gte619n.healthfitness.core.goals.chat.UserHealthSnapshotService;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramValidator;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatMessage;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatRepository;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatThread;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutProgramChatClient;
import java.time.Instant;
import java.util.ArrayList;
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
    private final WorkoutProgramService service;
    private final WorkoutProgramValidator validator;
    private final WorkoutProgramAssembler assembler;
    private final SseStreamer sseStreamer;

    public WorkoutProgramChatController(
        CurrentUserProvider currentUser,
        WorkoutProgramChatClient chatClient,
        WorkoutProgramChatRepository chat,
        UserHealthSnapshotService snapshots,
        ExerciseAvailabilityService availability,
        WorkoutProgramService service,
        WorkoutProgramValidator validator,
        WorkoutProgramAssembler assembler,
        SseStreamer sseStreamer
    ) {
        this.currentUser = currentUser;
        this.chatClient = chatClient;
        this.chat = chat;
        this.snapshots = snapshots;
        this.availability = availability;
        this.service = service;
        this.validator = validator;
        this.assembler = assembler;
        this.sseStreamer = sseStreamer;
    }

    /** The pre-chat form selections, sent on the FIRST message to open a thread. */
    public record ChatRequest(String threadId, String message, ProgramSchedule schedule, String goalId) {}
    public record ThreadResponse(String threadId, String title, ProgramSchedule schedule, String goalId,
                                 Instant createdAt, Instant updatedAt) {
        static ThreadResponse from(WorkoutProgramChatThread t) {
            return new ThreadResponse(t.threadId(), t.title(), t.schedule(), t.goalId(), t.createdAt(), t.updatedAt());
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

        final String context = buildContext(userId, thread.schedule());
        final ProgramSchedule schedule = thread.schedule();
        final String goalId = thread.goalId();
        final String threadId = thread.threadId();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseStreamer.stream(() -> {
            StringBuilder assistantText = new StringBuilder();
            try {
                WorkoutProgramChatClient.StreamResult result = chatClient.streamChat(
                    history, message, context, token -> {
                        assistantText.append(token);
                        SseEvents.send(emitter, JSON, "token", Map.of("text", token));
                    });

                String proposalJson = null;
                if (result.proposal() != null) {
                    WorkoutProgram p = result.proposal();
                    // Stamp userId + the form's schedule/goal so the proposal
                    // is grounded in the user's chosen gyms.
                    WorkoutProgram withUser = new WorkoutProgram(
                        userId, null, p.title(), p.description(), goalId, ProgramStatus.DRAFT,
                        p.source(), p.startDate(), schedule, p.phaseOrder(), p.phases(), null, null, null);
                    List<String> issues = validator.validate(userId, withUser);
                    WorkoutProgramDeepResponse deep = assembler.deep(withUser);
                    Map<String, Object> payload = Map.of("program", deep, "issues", issues);
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
        WorkoutProgram input = new WorkoutProgram(
            userId, null, body.title(), body.description(), goalId, ProgramStatus.DRAFT,
            source, body.startDate(), schedule, null, body.phases(), null, null, null);
        List<String> issues = validator.validate(userId, input);
        if (!issues.isEmpty()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("issues", issues));
        }
        WorkoutProgram created = service.create(input);
        return ResponseEntity.status(HttpStatus.CREATED).body(assembler.deep(created));
    }

    /** Health snapshot + STRICTLY per-gym allow-lists, scoped to the form's gyms. */
    private String buildContext(String userId, ProgramSchedule schedule) {
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
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
