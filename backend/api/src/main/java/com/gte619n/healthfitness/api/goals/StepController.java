package com.gte619n.healthfitness.api.goals;

import com.gte619n.healthfitness.api.goals.dto.CreateStepRequest;
import com.gte619n.healthfitness.api.goals.dto.ReorderRequest;
import com.gte619n.healthfitness.api.goals.dto.StepMetricBindingDto;
import com.gte619n.healthfitness.api.goals.dto.StepResponse;
import com.gte619n.healthfitness.api.goals.dto.UpdateStepRequest;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.api.sync.WriteResult;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.goals.GoalService;
import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepMetricBinding;
import com.gte619n.healthfitness.core.goals.StepRepository;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/me/goals/{goalId}/phases/{phaseId}/steps")
public class StepController {

    private final CurrentUserProvider currentUser;
    private final PhaseRepository phases;
    private final StepRepository steps;
    private final GoalService service;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public StepController(
        CurrentUserProvider currentUser,
        PhaseRepository phases,
        StepRepository steps,
        GoalService service,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.phases = phases;
        this.steps = steps;
        this.service = service;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    @PostMapping
    public ResponseEntity<WriteResult<StepResponse>> create(
        @PathVariable String goalId,
        @PathVariable String phaseId,
        @RequestBody CreateStepRequest body
    ) {
        if (body.title() == null || body.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (body.kind() == null) {
            throw new IllegalArgumentException("kind is required");
        }
        validateMetricForKind(body.kind(), body.metric());

        String userId = currentUser.get().userId();
        Phase phase = phases.findById(userId, goalId, phaseId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        int orderIndex = phase.stepOrder() == null ? 0 : phase.stepOrder().size();
        // Client-minted id + idempotent replay (IMPL-AND-20 D7); replay returns
        // the existing step WITHOUT re-appending it to the phase's stepOrder.
        String stepId = syncWrite.resolveId(body.id());
        StepMetricBinding metric = body.metric() != null ? body.metric().toModel() : null;
        WriteResult<StepResponse> response = syncWrite.idempotentCreate(
            "goals:steps:create:" + goalId + ":" + phaseId,
            userId,
            () -> {
                Instant writtenAt = Instant.now();
                Step step = new Step(
                    goalId,
                    phaseId,
                    stepId,
                    body.title(),
                    orderIndex,
                    body.kind(),
                    false,
                    null,
                    false,
                    metric
                );
                steps.save(userId, step);
                service.appendStepId(userId, goalId, phaseId, stepId);
                // Subcollection delta name: goals/phases/steps.
                syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals/phases/steps");
                return new SyncWriteContext.Created<>(
                    stepId, WriteResult.of(StepResponse.from(step), writtenAt));
            },
            id -> steps.findById(userId, goalId, phaseId, id)
                .map(s -> WriteResult.of(StepResponse.from(s), Instant.now()))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{stepId}")
    public StepResponse update(
        @PathVariable String goalId,
        @PathVariable String phaseId,
        @PathVariable String stepId,
        @RequestBody UpdateStepRequest body
    ) {
        String userId = currentUser.get().userId();
        Step existing = steps.findById(userId, goalId, phaseId, stepId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        StepKind kind = body.kind() != null ? body.kind() : existing.kind();
        StepMetricBinding metric = existing.metric();
        if (body.metric() != null) {
            metric = body.metric().toModel();
        }
        // Validate the resulting (kind, metric) pair if either changed.
        if (body.kind() != null || body.metric() != null) {
            validateMetricForKind(kind, metric == null ? null : StepMetricBindingDto.from(metric));
        }

        String title = body.title() != null ? body.title() : existing.title();

        // Update non-done fields first.
        Step updatedNonDone = new Step(
            existing.goalId(),
            existing.phaseId(),
            existing.stepId(),
            title,
            existing.orderIndex(),
            kind,
            existing.done(),
            existing.doneAt(),
            existing.manualOverride(),
            metric
        );
        steps.save(userId, updatedNonDone);
        // One fan-out for the whole update covers all branches below (each
        // persists through this save or a subsequent markStepDone save).
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals/phases/steps");

        // Reset-to-auto wins over `done` if both are sent.
        if (Boolean.TRUE.equals(body.resetToAuto())) {
            Step cleared = new Step(
                updatedNonDone.goalId(),
                updatedNonDone.phaseId(),
                updatedNonDone.stepId(),
                updatedNonDone.title(),
                updatedNonDone.orderIndex(),
                updatedNonDone.kind(),
                updatedNonDone.done(),
                updatedNonDone.doneAt(),
                false,
                updatedNonDone.metric()
            );
            steps.save(userId, cleared);
            return StepResponse.from(cleared);
        }

        if (body.done() != null) {
            // Manual check / un-check — manualOverride = true.
            Step result = service.markStepDone(userId, goalId, phaseId, stepId, body.done(), true);
            return StepResponse.from(result);
        }

        return StepResponse.from(updatedNonDone);
    }

    @DeleteMapping("/{stepId}")
    public ResponseEntity<Void> delete(
        @PathVariable String goalId,
        @PathVariable String phaseId,
        @PathVariable String stepId
    ) {
        String userId = currentUser.get().userId();
        if (steps.findById(userId, goalId, phaseId, stepId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Hard delete + remove from Phase's stepOrder in the same op.
        service.removeStepId(userId, goalId, phaseId, stepId);
        steps.delete(userId, goalId, phaseId, stepId);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals/phases/steps");
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    public ResponseEntity<Void> reorder(
        @PathVariable String goalId,
        @PathVariable String phaseId,
        @RequestBody ReorderRequest body
    ) {
        if (body == null || body.ids() == null) {
            throw new IllegalArgumentException("ids is required");
        }
        String userId = currentUser.get().userId();
        if (phases.findById(userId, goalId, phaseId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.reorderSteps(userId, goalId, phaseId, body.ids());
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals/phases/steps");
        return ResponseEntity.noContent().build();
    }

    private static void validateMetricForKind(StepKind kind, StepMetricBindingDto metric) {
        if (kind == StepKind.MANUAL) {
            if (metric != null) {
                throw new IllegalArgumentException("metric must be null for MANUAL kind");
            }
            return;
        }
        if (metric == null) {
            throw new IllegalArgumentException("metric is required for kind " + kind);
        }
        if (metric.metricKey() == null || metric.metricKey().isBlank()) {
            throw new IllegalArgumentException("metric.metricKey is required");
        }
        if (metric.comparator() == null) {
            throw new IllegalArgumentException("metric.comparator is required");
        }
        if (metric.targetValue() == null) {
            throw new IllegalArgumentException("metric.targetValue is required");
        }
        if (kind == StepKind.SUSTAINED) {
            if (metric.windowDays() == null || metric.windowDays() <= 0) {
                throw new IllegalArgumentException("metric.windowDays must be > 0 for SUSTAINED kind");
            }
        } else if (metric.windowDays() != null) {
            throw new IllegalArgumentException("metric.windowDays only allowed for SUSTAINED kind");
        }
        // countFrom is optional even for COUNT (defaults to "now" in resolver / step creation).
    }

}
