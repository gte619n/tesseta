package com.gte619n.healthfitness.api.goals;

import com.gte619n.healthfitness.api.goals.dto.CreatePhaseRequest;
import com.gte619n.healthfitness.api.goals.dto.PhaseResponse;
import com.gte619n.healthfitness.api.goals.dto.ReorderRequest;
import com.gte619n.healthfitness.api.goals.dto.UpdatePhaseRequest;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.api.sync.WriteResult;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalService;
import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.PhaseStatus;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
@RequestMapping("/api/me/goals/{goalId}/phases")
public class PhaseController {

    private final CurrentUserProvider currentUser;
    private final GoalRepository goals;
    private final PhaseRepository phases;
    private final GoalService service;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public PhaseController(
        CurrentUserProvider currentUser,
        GoalRepository goals,
        PhaseRepository phases,
        GoalService service,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.goals = goals;
        this.phases = phases;
        this.service = service;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    @PostMapping
    public ResponseEntity<WriteResult<PhaseResponse>> create(
        @PathVariable String goalId,
        @RequestBody CreatePhaseRequest body
    ) {
        if (body.title() == null || body.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String userId = currentUser.get().userId();
        Goal goal = goals.findById(userId, goalId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // First Phase in a Goal starts ACTIVE; the rest LOCKED.
        List<String> order = goal.phaseOrder() == null ? List.of() : goal.phaseOrder();
        PhaseStatus status = order.isEmpty() ? PhaseStatus.ACTIVE : PhaseStatus.LOCKED;
        int orderIndex = order.size();

        // Client-minted id + idempotent replay (IMPL-AND-20 D7). The replay
        // returns the existing phase WITHOUT re-appending it to phaseOrder.
        String phaseId = syncWrite.resolveId(body.id());
        WriteResult<PhaseResponse> response = syncWrite.idempotentCreate(
            "goals:phases:create:" + goalId,
            userId,
            () -> {
                Instant writtenAt = Instant.now();
                Phase phase = new Phase(
                    goalId,
                    phaseId,
                    body.title(),
                    body.description(),
                    orderIndex,
                    status,
                    body.targetStartDate(),
                    body.targetEndDate(),
                    null,
                    List.of()
                );
                phases.save(userId, phase);
                service.appendPhaseId(userId, goalId, phaseId);
                // Subcollection delta name (FirestoreSyncChangeReader): goals/phases.
                syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals/phases");
                return new SyncWriteContext.Created<>(
                    phaseId, WriteResult.of(PhaseResponse.from(phase, List.of()), writtenAt));
            },
            id -> phases.findById(userId, goalId, id)
                .map(p -> WriteResult.of(PhaseResponse.from(p, List.of()), Instant.now()))
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{phaseId}")
    public PhaseResponse update(
        @PathVariable String goalId,
        @PathVariable String phaseId,
        @RequestBody UpdatePhaseRequest body
    ) {
        String userId = currentUser.get().userId();
        Phase existing = phases.findById(userId, goalId, phaseId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String title = body.title() != null ? body.title() : existing.title();
        String description = body.description() != null ? body.description() : existing.description();
        LocalDate targetStartDate = body.targetStartDate() != null ? body.targetStartDate() : existing.targetStartDate();
        LocalDate targetEndDate = body.targetEndDate() != null ? body.targetEndDate() : existing.targetEndDate();

        Phase updated = new Phase(
            existing.goalId(),
            existing.phaseId(),
            title,
            description,
            existing.orderIndex(),
            existing.status(),
            targetStartDate,
            targetEndDate,
            existing.completedAt(),
            existing.stepOrder()
        );
        phases.save(userId, updated);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals/phases");
        return PhaseResponse.from(updated, List.of());
    }

    @DeleteMapping("/{phaseId}")
    public ResponseEntity<Void> delete(
        @PathVariable String goalId,
        @PathVariable String phaseId
    ) {
        String userId = currentUser.get().userId();
        if (phases.findById(userId, goalId, phaseId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Hard delete + remove from parent Goal's phaseOrder in the same op.
        service.removePhaseId(userId, goalId, phaseId);
        phases.delete(userId, goalId, phaseId);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals/phases");
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    public ResponseEntity<Void> reorder(
        @PathVariable String goalId,
        @RequestBody ReorderRequest body
    ) {
        if (body == null || body.ids() == null) {
            throw new IllegalArgumentException("ids is required");
        }
        String userId = currentUser.get().userId();
        if (goals.findById(userId, goalId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.reorderPhases(userId, goalId, body.ids());
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "goals/phases");
        return ResponseEntity.noContent().build();
    }
}
