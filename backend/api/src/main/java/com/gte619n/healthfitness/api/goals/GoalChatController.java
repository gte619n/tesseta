package com.gte619n.healthfitness.api.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gte619n.healthfitness.api.goals.dto.ChatMessageRequest;
import com.gte619n.healthfitness.api.goals.dto.ChatThreadResponse;
import com.gte619n.healthfitness.api.goals.dto.CommitResponse;
import com.gte619n.healthfitness.api.goals.dto.GoalProposalDto;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalService;
import com.gte619n.healthfitness.core.goals.GoalSource;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.PhaseStatus;
import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepMetricBinding;
import com.gte619n.healthfitness.core.goals.StepRepository;
import com.gte619n.healthfitness.core.goals.chat.ChatRole;
import com.gte619n.healthfitness.core.goals.chat.GoalChatMessage;
import com.gte619n.healthfitness.core.goals.chat.GoalChatRepository;
import com.gte619n.healthfitness.core.goals.chat.GoalChatThread;
import com.gte619n.healthfitness.core.goals.chat.GoalProposal;
import com.gte619n.healthfitness.core.goals.chat.GoalProposalValidator;
import com.gte619n.healthfitness.core.goals.eval.StepEvaluationService;
import com.gte619n.healthfitness.integrations.goals.GoalChatClient;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Goals chat: Gemini designs Goal/Phase/Step roadmaps through tool
 * calling, the user reviews/edits the proposal, then commits it.
 *
 * <ul>
 *   <li>{@code POST /api/me/goals/chat} — SSE. Persists the user message,
 *       streams {@code token} text deltas, a validated {@code proposal}
 *       event when the model calls the tool, then a terminal {@code done}.
 *       Persists the assistant message (with proposalJson) when the stream
 *       finishes.</li>
 *   <li>{@code POST /api/me/goals/chat/{threadId}/commit} — re-validates the
 *       (user-edited) proposal and, if valid, creates the Goal/Phases/Steps
 *       via the same {@link GoalService}/repository methods the manual CRUD
 *       uses, runs an initial evaluation, and returns the new goalId.</li>
 *   <li>{@code GET /api/me/goals/chat/threads} — list the user's threads.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/me/goals/chat")
public class GoalChatController {

    private static final long SSE_TIMEOUT_MS = 120_000L;

    private static final ObjectMapper JSON = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final CurrentUserProvider currentUser;
    private final GoalChatRepository chat;
    private final GoalChatClient chatClient;
    private final GoalProposalValidator validator;
    private final GoalService service;
    private final GoalRepository goals;
    private final PhaseRepository phases;
    private final StepRepository steps;
    private final StepEvaluationService evaluator;

    public GoalChatController(
        CurrentUserProvider currentUser,
        GoalChatRepository chat,
        GoalChatClient chatClient,
        GoalProposalValidator validator,
        GoalService service,
        GoalRepository goals,
        PhaseRepository phases,
        StepRepository steps,
        StepEvaluationService evaluator
    ) {
        this.currentUser = currentUser;
        this.chat = chat;
        this.chatClient = chatClient;
        this.validator = validator;
        this.service = service;
        this.goals = goals;
        this.phases = phases;
        this.steps = steps;
        this.evaluator = evaluator;
    }

    // ---- POST /api/me/goals/chat (SSE) ----

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatMessageRequest body) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        final String userId = currentUser.get().userId();
        final String message = body.message();

        // Resolve or lazily create the thread synchronously so a bad
        // threadId surfaces as 4xx before the stream opens.
        final String threadId;
        if (body.threadId() != null && !body.threadId().isBlank()) {
            threadId = body.threadId();
            if (chat.findThread(userId, threadId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat thread not found");
            }
        } else {
            threadId = UUID.randomUUID().toString();
            chat.createThread(new GoalChatThread(userId, threadId, deriveTitle(message), null, null));
        }

        // Load prior history BEFORE persisting the new user message.
        List<GoalChatClient.Turn> history = new ArrayList<>();
        for (GoalChatMessage m : chat.listMessages(userId, threadId)) {
            history.add(new GoalChatClient.Turn(m.role() == ChatRole.USER, m.content()));
        }

        // Persist the user message.
        chat.appendMessage(userId, new GoalChatMessage(
            threadId, UUID.randomUUID().toString(), ChatRole.USER, message, null, null));

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.startVirtualThread(() -> {
            StringBuilder assistantText = new StringBuilder();
            try {
                GoalChatClient.StreamResult result = chatClient.streamChat(history, message, token -> {
                    assistantText.append(token);
                    sendEvent(emitter, "token", Map.of("text", token));
                });

                String proposalJson = null;
                if (result.proposal() != null) {
                    GoalProposal validated = validator.validate(result.proposal());
                    GoalProposalDto dto = GoalProposalDto.from(validated);
                    proposalJson = JSON.writeValueAsString(dto);
                    sendEvent(emitter, "proposal", dto);
                }

                // Persist the assistant message once the stream completes.
                chat.appendMessage(userId, new GoalChatMessage(
                    threadId, UUID.randomUUID().toString(), ChatRole.ASSISTANT,
                    result.assistantText() != null ? result.assistantText() : assistantText.toString(),
                    proposalJson, null));

                sendEvent(emitter, "done", Map.of("threadId", threadId));
                emitter.complete();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Chat failed" : e.getMessage();
                sendEvent(emitter, "error", Map.of("error", msg));
                emitter.complete();
            }
        });
        return emitter;
    }

    // ---- POST /api/me/goals/chat/{threadId}/commit ----

    @PostMapping("/{threadId}/commit")
    public ResponseEntity<?> commit(
        @PathVariable String threadId,
        @RequestBody GoalProposalDto body
    ) {
        String userId = currentUser.get().userId();
        if (chat.findThread(userId, threadId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat thread not found");
        }
        if (body == null) {
            throw new IllegalArgumentException("proposal is required");
        }

        GoalProposal validated = validator.validate(body.toRaw());
        if (!validated.isValid()) {
            // Return the flagged structure as the 400 body so the UI can
            // re-render the card with inline errors rather than guessing.
            return ResponseEntity.badRequest().body(GoalProposalDto.from(validated));
        }

        // Create the Goal, then its Phases and Steps in order, reusing the
        // SAME service/repository methods the manual CRUD controllers use.
        String goalId = UUID.randomUUID().toString();
        LocalDate startDate = LocalDate.now();
        Goal goal = new Goal(
            userId, goalId,
            validated.title(), validated.description(), validated.domain(),
            GoalStatus.ACTIVE, startDate, validated.targetDate(),
            null, null, null, List.of(), GoalSource.AI_ASSISTED);
        goals.save(goal);

        List<GoalProposal.ProposalPhase> proposalPhases =
            validated.phases() == null ? List.of() : validated.phases();
        for (int pi = 0; pi < proposalPhases.size(); pi++) {
            GoalProposal.ProposalPhase pp = proposalPhases.get(pi);
            String phaseId = UUID.randomUUID().toString();
            // First Phase ACTIVE, the rest LOCKED — same rule as PhaseController.
            PhaseStatus phaseStatus = pi == 0 ? PhaseStatus.ACTIVE : PhaseStatus.LOCKED;
            Phase phase = new Phase(
                goalId, phaseId, pp.title(), pp.description(), pi, phaseStatus,
                pp.targetStartDate(), pp.targetEndDate(), null, List.of());
            phases.save(userId, phase);
            service.appendPhaseId(userId, goalId, phaseId);

            List<GoalProposal.ProposalStep> proposalSteps =
                pp.steps() == null ? List.of() : pp.steps();
            for (int si = 0; si < proposalSteps.size(); si++) {
                GoalProposal.ProposalStep ps = proposalSteps.get(si);
                String stepId = UUID.randomUUID().toString();
                StepMetricBinding metric = null;
                if (ps.metric() != null && ps.kind() != StepKind.MANUAL) {
                    GoalProposal.ProposalMetric m = ps.metric();
                    metric = new StepMetricBinding(
                        m.metricKey(), m.comparator(),
                        m.targetValue() != null ? m.targetValue() : 0.0,
                        m.windowDays(), m.countFrom());
                }
                Step step = new Step(
                    goalId, phaseId, stepId, ps.title(), si, ps.kind(),
                    false, null, false, metric);
                steps.save(userId, step);
                service.appendStepId(userId, goalId, phaseId, stepId);
            }
        }

        // Initial evaluation so already-satisfied Steps auto-check.
        evaluator.evaluateGoal(userId, goalId);

        return ResponseEntity.ok(new CommitResponse(goalId));
    }

    // ---- GET /api/me/goals/chat/threads ----

    @GetMapping("/threads")
    public List<ChatThreadResponse> threads() {
        String userId = currentUser.get().userId();
        return chat.listThreads(userId).stream()
            .map(ChatThreadResponse::from)
            .toList();
    }

    // ---- helpers ----

    private static String deriveTitle(String firstMessage) {
        String trimmed = firstMessage.strip();
        if (trimmed.length() <= 60) return trimmed;
        return trimmed.substring(0, 57) + "...";
    }

    private static void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event()
                .name(name)
                .data(JSON.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
