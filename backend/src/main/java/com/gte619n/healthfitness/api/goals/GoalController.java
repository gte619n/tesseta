package com.gte619n.healthfitness.api.goals;

import com.gte619n.healthfitness.api.goals.dto.CreateGoalRequest;
import com.gte619n.healthfitness.api.goals.dto.GoalDeepResponse;
import com.gte619n.healthfitness.api.goals.dto.GoalResponse;
import com.gte619n.healthfitness.api.goals.dto.PhaseResponse;
import com.gte619n.healthfitness.api.goals.dto.StepResponse;
import com.gte619n.healthfitness.api.goals.dto.UpdateGoalRequest;
import com.gte619n.healthfitness.api.nutrition.MacrosDto;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.api.sync.WriteResult;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalService;
import com.gte619n.healthfitness.core.goals.GoalSource;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepRepository;
import com.gte619n.healthfitness.core.goals.eval.StepEvaluationService;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/me/goals")
public class GoalController {

    private final CurrentUserProvider currentUser;
    private final GoalRepository goals;
    private final PhaseRepository phases;
    private final StepRepository steps;
    private final GoalService service;
    private final StepEvaluationService evaluator;
    private final GoalNutritionService goalNutrition;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public GoalController(
        CurrentUserProvider currentUser,
        GoalRepository goals,
        PhaseRepository phases,
        StepRepository steps,
        GoalService service,
        StepEvaluationService evaluator,
        GoalNutritionService goalNutrition,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.goals = goals;
        this.phases = phases;
        this.steps = steps;
        this.service = service;
        this.evaluator = evaluator;
        this.goalNutrition = goalNutrition;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    @GetMapping
    public List<GoalResponse> list(@RequestParam(value = "status", required = false) GoalStatus status) {
        String userId = currentUser.get().userId();
        return goals.findByUser(userId, status).stream()
            .map(GoalResponse::from)
            .toList();
    }

    @PostMapping
    public ResponseEntity<WriteResult<GoalResponse>> create(@RequestBody CreateGoalRequest body) {
        if (body.title() == null || body.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (body.domain() == null) {
            throw new IllegalArgumentException("domain is required");
        }
        String userId = currentUser.get().userId();
        // Client-minted id + idempotent replay (IMPL-AND-20 D7).
        String goalId = syncWrite.resolveId(body.id());
        LocalDate startDate = body.startDate() != null ? body.startDate() : LocalDate.now();
        GoalSource source = body.source() != null ? body.source() : GoalSource.MANUAL;

        WriteResult<GoalResponse> response = syncWrite.idempotentCreate(
            "goals:create",
            userId,
            () -> {
                Instant writtenAt = Instant.now();
                Goal goal = new Goal(
                    userId,
                    goalId,
                    body.title(),
                    body.description(),
                    body.domain(),
                    GoalStatus.ACTIVE,
                    startDate,
                    body.targetDate(),
                    writtenAt,
                    writtenAt,
                    null,
                    List.of(),
                    source
                );
                goals.save(goal);
                syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals");
                return new SyncWriteContext.Created<>(
                    goalId, WriteResult.of(GoalResponse.from(goal), writtenAt));
            },
            id -> goals.findById(userId, id)
                .map(g -> WriteResult.of(GoalResponse.from(g), g.updatedAt()))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{goalId}")
    public GoalDeepResponse getDeep(@PathVariable String goalId) {
        String userId = currentUser.get().userId();
        Goal initial = goals.findById(userId, goalId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Run a fresh evaluation BEFORE loading phases/steps so the
        // response reflects the same current metric truth the UI will
        // render. evaluateGoal may flip Step.done and (via GoalService)
        // cascade phase/goal completion — re-read the Goal afterwards
        // so we pick up any sticky status changes.
        evaluator.evaluateGoal(userId, goalId);
        Goal goal = goals.findById(userId, goalId).orElse(initial);

        List<Phase> phaseList = phases.findByGoal(userId, goalId);
        Map<String, Phase> phasesById = new HashMap<>();
        for (Phase p : phaseList) phasesById.put(p.phaseId(), p);

        List<String> order = goal.phaseOrder() == null ? List.of() : goal.phaseOrder();
        List<PhaseResponse> phaseResponses = new ArrayList<>();

        // Walk phaseOrder; fall back to any phases not in the order at the end.
        List<String> seen = new ArrayList<>();
        for (String pid : order) {
            Phase p = phasesById.get(pid);
            if (p == null) continue;
            phaseResponses.add(toPhaseResponse(userId, p));
            seen.add(pid);
        }
        for (Phase p : phaseList) {
            if (!seen.contains(p.phaseId())) {
                phaseResponses.add(toPhaseResponse(userId, p));
            }
        }

        return GoalDeepResponse.from(goal, phaseResponses);
    }

    private PhaseResponse toPhaseResponse(String userId, Phase phase) {
        List<Step> phaseSteps = steps.findByPhase(userId, phase.goalId(), phase.phaseId());
        List<String> stepOrder = phase.stepOrder() == null ? List.of() : phase.stepOrder();
        Map<String, Step> byId = new HashMap<>();
        for (Step s : phaseSteps) byId.put(s.stepId(), s);

        List<StepResponse> ordered = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String sid : stepOrder) {
            Step s = byId.get(sid);
            if (s == null) continue;
            ordered.add(toStepResponse(userId, s));
            seen.add(sid);
        }
        for (Step s : phaseSteps) {
            if (!seen.contains(s.stepId())) {
                ordered.add(toStepResponse(userId, s));
            }
        }
        return PhaseResponse.from(phase, ordered);
    }

    /**
     * Wrap a Step in its response DTO. For metric-bound done Steps that
     * aren't manually overridden, ask the evaluator whether the metric
     * has regressed and surface the flag. For everything else
     * (manualOverride, MANUAL kind, undone), the flag is null so the
     * UI can omit the "metric regressed" badge entirely.
     */
    private StepResponse toStepResponse(String userId, Step s) {
        boolean qualifies =
            s.done()
            && s.kind() != com.gte619n.healthfitness.core.goals.StepKind.MANUAL
            && !s.manualOverride()
            && s.metric() != null;
        Boolean regressed = qualifies ? evaluator.computeRegressionFlag(userId, s) : null;
        return StepResponse.from(s, regressed);
    }

    @PatchMapping("/{goalId}")
    public WriteResult<GoalResponse> update(@PathVariable String goalId, @RequestBody UpdateGoalRequest body) {
        String userId = currentUser.get().userId();
        Goal existing = goals.findById(userId, goalId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String title = body.title() != null ? body.title() : existing.title();
        String description = body.description() != null ? body.description() : existing.description();
        GoalDomain domain = body.domain() != null ? body.domain() : existing.domain();
        // Sticky: never reverse COMPLETED via PATCH.
        GoalStatus status = body.status();
        if (status == null) {
            status = existing.status();
        } else if (existing.status() == GoalStatus.COMPLETED && status != GoalStatus.COMPLETED) {
            status = GoalStatus.COMPLETED;
        }
        LocalDate startDate = body.startDate() != null ? body.startDate() : existing.startDate();
        LocalDate targetDate = body.targetDate() != null ? body.targetDate() : existing.targetDate();

        Instant writtenAt = Instant.now();
        Goal updated = new Goal(
            existing.userId(),
            existing.goalId(),
            title,
            description,
            domain,
            status,
            startDate,
            targetDate,
            existing.createdAt(),
            writtenAt,
            existing.completedAt(),
            existing.phaseOrder(),
            existing.source()
        );
        goals.save(updated);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals");
        return WriteResult.of(GoalResponse.from(updated), writtenAt);
    }

    @DeleteMapping("/{goalId}")
    public ResponseEntity<Void> archive(@PathVariable String goalId) {
        String userId = currentUser.get().userId();
        if (goals.findById(userId, goalId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.archive(userId, goalId);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{goalId}/reevaluate")
    public ResponseEntity<Void> reevaluate(@PathVariable String goalId) {
        String userId = currentUser.get().userId();
        if (goals.findById(userId, goalId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        evaluator.evaluateGoal(userId, goalId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The nutrition guidance this goal can apply (from its linked program), used
     * to show/enable the "Update nutrition" action and preview the macros. 204
     * when the goal has no linked program with guidance OR the user's current
     * target already matches it (applying would be a no-op).
     */
    @GetMapping("/{goalId}/nutrition-guidance")
    public ResponseEntity<NutritionGuidance> nutritionGuidance(@PathVariable String goalId) {
        String userId = currentUser.get().userId();
        if (goals.findById(userId, goalId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return goalNutrition.guidanceToApply(userId, goalId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Apply the goal's linked-program nutrition guidance as the user's macro
     * target (calories re-derived from the macros). 409 when the goal has no
     * guidance to apply.
     */
    @PostMapping("/{goalId}/nutrition-target")
    public MacrosDto applyNutritionTarget(@PathVariable String goalId) {
        String userId = currentUser.get().userId();
        if (goals.findById(userId, goalId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Macros applied = goalNutrition.applyToTarget(userId, goalId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This goal has no linked program nutrition guidance to apply."));
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "nutritionTargets");
        return MacrosDto.from(applied);
    }
}
