package com.gte619n.healthfitness.api.workoutprogram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.goals.chat.UserHealthSnapshotService;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramValidator;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutProgramChatClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Workout-program designer chat. {@code POST /chat} streams the assistant's
 * tokens and a validated {@code proposal} event over SSE; {@code POST
 * /chat/commit} validates the (edited) program and persists it.
 *
 * <p>v1 is stateless — the client supplies prior turns in the request rather
 * than the server persisting threads (a follow-up can add thread storage).
 */
@RestController
@RequestMapping("/api/me/workout-programs/chat")
public class WorkoutProgramChatController {

    private static final Logger log = LoggerFactory.getLogger(WorkoutProgramChatController.class);
    private static final long SSE_TIMEOUT_MS = 180_000L;
    private static final ObjectMapper JSON = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final CurrentUserProvider currentUser;
    private final WorkoutProgramChatClient chatClient;
    private final UserHealthSnapshotService snapshots;
    private final LocationRepository locations;
    private final ExerciseAvailabilityService availability;
    private final WorkoutProgramService service;
    private final WorkoutProgramValidator validator;
    private final WorkoutProgramAssembler assembler;

    public WorkoutProgramChatController(
        CurrentUserProvider currentUser,
        WorkoutProgramChatClient chatClient,
        UserHealthSnapshotService snapshots,
        LocationRepository locations,
        ExerciseAvailabilityService availability,
        WorkoutProgramService service,
        WorkoutProgramValidator validator,
        WorkoutProgramAssembler assembler
    ) {
        this.currentUser = currentUser;
        this.chatClient = chatClient;
        this.snapshots = snapshots;
        this.locations = locations;
        this.availability = availability;
        this.service = service;
        this.validator = validator;
        this.assembler = assembler;
    }

    public record ChatTurn(String role, String content) {}
    public record ChatRequest(String message, List<ChatTurn> history) {}

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest body) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        final String userId = currentUser.get().userId();
        final String message = body.message();
        final List<WorkoutProgramChatClient.Turn> history = new ArrayList<>();
        if (body.history() != null) {
            for (ChatTurn t : body.history()) {
                if (t == null || t.content() == null || t.content().isBlank()) continue;
                history.add(new WorkoutProgramChatClient.Turn(
                    t.role() == null || t.role().equalsIgnoreCase("user"), t.content()));
            }
        }
        final String context = buildContext(userId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.startVirtualThread(() -> {
            try {
                WorkoutProgramChatClient.StreamResult result = chatClient.streamChat(
                    history, message, context, token -> sendEvent(emitter, "token", Map.of("text", token)));

                if (result.proposal() != null) {
                    // Stamp the userId so the assembler can resolve summaries/gyms,
                    // then validate and stream the editable proposal + any issues.
                    WorkoutProgram p = result.proposal();
                    WorkoutProgram withUser = new WorkoutProgram(
                        userId, null, p.title(), p.description(), p.goalId(), ProgramStatus.DRAFT,
                        p.source(), p.startDate(), p.schedule(), p.phaseOrder(), p.phases(), null, null, null);
                    List<String> issues = validator.validate(userId, withUser);
                    sendEvent(emitter, "proposal", Map.of(
                        "program", assembler.deep(withUser),
                        "issues", issues));
                }
                sendEvent(emitter, "done", Map.of());
                emitter.complete();
            } catch (Exception e) {
                log.error("Workout-program chat failed for user {}: {}", userId, e.toString(), e);
                sendEvent(emitter, "error", Map.of("error", e.getMessage() == null ? "Chat failed" : e.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    @PostMapping("/commit")
    public ResponseEntity<?> commit(@RequestBody CreateProgramRequest body) {
        String userId = currentUser.get().userId();
        WorkoutProgram input = new WorkoutProgram(
            userId, null, body.title(), body.description(), body.goalId(),
            ProgramStatus.DRAFT, body.source(), body.startDate(), body.schedule(),
            null, body.phases(), null, null, null);
        // Validate against the catalog + equipment before persisting; flag inline.
        List<String> issues = validator.validate(userId, input);
        if (!issues.isEmpty()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("issues", issues));
        }
        WorkoutProgram created = service.create(input);
        return ResponseEntity.status(HttpStatus.CREATED).body(assembler.deep(created));
    }

    /** Health snapshot + per-gym executable-exercise allow-lists. */
    private String buildContext(String userId) {
        StringBuilder sb = new StringBuilder();
        try {
            String snapshot = snapshots.buildSnapshot(userId);
            if (snapshot != null && !snapshot.isBlank()) {
                sb.append("USER HEALTH SNAPSHOT:\n").append(snapshot).append("\n\n");
            }
        } catch (Exception e) {
            log.warn("Health snapshot unavailable for {}: {}", userId, e.getMessage());
        }
        sb.append("AVAILABLE GYMS AND THEIR EXECUTABLE EXERCISES (use only these "
            + "exerciseIds, and only at the matching locationId):\n");
        List<Location> gyms = locations.findByUser(userId, false);
        if (gyms.isEmpty()) {
            sb.append("  (no gyms configured — ask the user to add a gym first)\n");
        }
        for (Location gym : gyms) {
            sb.append("- Gym \"").append(gym.name()).append("\" (locationId=")
                .append(gym.locationId()).append("):\n");
            List<Exercise> executable = availability.executableAt(userId, gym.locationId());
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

    private static void sendEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(JSON.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // Client likely disconnected; nothing actionable.
        }
    }
}
