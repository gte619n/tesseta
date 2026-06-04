package com.gte619n.healthfitness.core.goals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Orchestrates Goal/Phase/Step lifecycle. Owns:
 *  - phaseOrder / stepOrder array maintenance
 *  - soft archive on Goal delete (status = ARCHIVED)
 *  - all writes to Step.done — Phase auto-progression depends on it
 *  - Phase/Goal completion (sticky — never reverses)
 *
 * Step evaluation against metrics lives in StepEvaluationService (Phase 3);
 * GoalService is the state-machine writer that StepEvaluationService calls.
 */
@Service
public class GoalService {

    private final GoalRepository goals;
    private final PhaseRepository phases;
    private final StepRepository steps;

    public GoalService(GoalRepository goals, PhaseRepository phases, StepRepository steps) {
        this.goals = goals;
        this.phases = phases;
        this.steps = steps;
    }

    /** Soft-delete the Goal: status -> ARCHIVED. Phases/Steps left intact. */
    public void archive(String userId, String goalId) {
        Goal existing = goals.findById(userId, goalId)
            .orElseThrow(() -> new IllegalArgumentException("Goal not found"));
        Goal archived = new Goal(
            existing.userId(),
            existing.goalId(),
            existing.title(),
            existing.description(),
            existing.domain(),
            GoalStatus.ARCHIVED,
            existing.startDate(),
            existing.targetDate(),
            existing.createdAt(),
            null,                       // updatedAt — Firestore impl substitutes serverTimestamp
            existing.completedAt(),
            existing.phaseOrder(),
            existing.source()
        );
        goals.save(archived);
    }

    /** Append a phaseId to the parent Goal's phaseOrder. */
    public void appendPhaseId(String userId, String goalId, String phaseId) {
        Goal goal = goals.findById(userId, goalId)
            .orElseThrow(() -> new IllegalArgumentException("Goal not found"));
        List<String> order = new ArrayList<>(goal.phaseOrder() == null ? List.of() : goal.phaseOrder());
        if (!order.contains(phaseId)) {
            order.add(phaseId);
        }
        goals.save(withPhaseOrder(goal, order));
    }

    /** Remove a phaseId from the parent Goal's phaseOrder in the same operation as the delete. */
    public void removePhaseId(String userId, String goalId, String phaseId) {
        Goal goal = goals.findById(userId, goalId)
            .orElseThrow(() -> new IllegalArgumentException("Goal not found"));
        List<String> order = new ArrayList<>(goal.phaseOrder() == null ? List.of() : goal.phaseOrder());
        order.remove(phaseId);
        goals.save(withPhaseOrder(goal, order));
    }

    /** Overwrite phaseOrder. Caller must have validated membership. */
    public void reorderPhases(String userId, String goalId, List<String> phaseIds) {
        Goal goal = goals.findById(userId, goalId)
            .orElseThrow(() -> new IllegalArgumentException("Goal not found"));
        List<String> existing = goal.phaseOrder() == null ? List.of() : goal.phaseOrder();
        if (phaseIds.size() != existing.size() || !phaseIds.containsAll(existing)) {
            throw new IllegalArgumentException("reorder must be a permutation of existing phaseIds");
        }
        goals.save(withPhaseOrder(goal, phaseIds));
    }

    /** Append a stepId to the parent Phase's stepOrder. */
    public void appendStepId(String userId, String goalId, String phaseId, String stepId) {
        Phase phase = phases.findById(userId, goalId, phaseId)
            .orElseThrow(() -> new IllegalArgumentException("Phase not found"));
        List<String> order = new ArrayList<>(phase.stepOrder() == null ? List.of() : phase.stepOrder());
        if (!order.contains(stepId)) {
            order.add(stepId);
        }
        phases.save(userId, withStepOrder(phase, order));
    }

    /** Remove a stepId from the parent Phase's stepOrder in the same operation as the delete. */
    public void removeStepId(String userId, String goalId, String phaseId, String stepId) {
        Phase phase = phases.findById(userId, goalId, phaseId)
            .orElseThrow(() -> new IllegalArgumentException("Phase not found"));
        List<String> order = new ArrayList<>(phase.stepOrder() == null ? List.of() : phase.stepOrder());
        order.remove(stepId);
        phases.save(userId, withStepOrder(phase, order));
    }

    /** Overwrite stepOrder. Caller must have validated membership. */
    public void reorderSteps(String userId, String goalId, String phaseId, List<String> stepIds) {
        Phase phase = phases.findById(userId, goalId, phaseId)
            .orElseThrow(() -> new IllegalArgumentException("Phase not found"));
        List<String> existing = phase.stepOrder() == null ? List.of() : phase.stepOrder();
        if (stepIds.size() != existing.size() || !stepIds.containsAll(existing)) {
            throw new IllegalArgumentException("reorder must be a permutation of existing stepIds");
        }
        phases.save(userId, withStepOrder(phase, stepIds));
    }

    /**
     * The ONLY path that writes Step.done. Centralizes:
     *  - done / doneAt / manualOverride mutation
     *  - Phase completion check + flip next Phase LOCKED -> ACTIVE
     *  - Goal completion when last Phase completes
     *  - Sticky completion: a COMPLETED Phase/Goal never goes back to ACTIVE,
     *    and completedAt is never cleared.
     *
     * When done transitions to true, doneAt is set to Instant.now().
     * When done is set to false (manual un-check), doneAt is left as-is.
     */
    public Step markStepDone(
        String userId,
        String goalId,
        String phaseId,
        String stepId,
        boolean done,
        boolean manualOverride
    ) {
        Step existing = steps.findById(userId, goalId, phaseId, stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found"));

        boolean wasDone = existing.done();
        Instant doneAt = existing.doneAt();
        if (done && !wasDone) {
            doneAt = Instant.now();
        }
        // When done is being set false, leave existing doneAt untouched (history).

        Step updated = new Step(
            existing.goalId(),
            existing.phaseId(),
            existing.stepId(),
            existing.title(),
            existing.orderIndex(),
            existing.kind(),
            done,
            doneAt,
            manualOverride,
            existing.metric()
        );
        steps.save(userId, updated);

        // Phase auto-progression only on true transitions (false -> true).
        if (done && !wasDone) {
            cascadeCompletion(userId, goalId, phaseId);
        }
        return updated;
    }

    private void cascadeCompletion(String userId, String goalId, String phaseId) {
        Phase phase = phases.findById(userId, goalId, phaseId).orElse(null);
        if (phase == null) return;
        // Sticky: never re-complete an already-completed Phase.
        if (phase.status() == PhaseStatus.COMPLETED) return;

        List<Step> phaseSteps = steps.findByPhase(userId, goalId, phaseId);
        if (phaseSteps.isEmpty()) return;
        for (Step s : phaseSteps) {
            if (!s.done()) return;
        }

        // All steps done — complete this Phase.
        Instant now = Instant.now();
        Phase completed = new Phase(
            phase.goalId(),
            phase.phaseId(),
            phase.title(),
            phase.description(),
            phase.orderIndex(),
            PhaseStatus.COMPLETED,
            phase.targetStartDate(),
            phase.targetEndDate(),
            now,
            phase.stepOrder()
        );
        phases.save(userId, completed);

        // Activate next Phase by phaseOrder, if any.
        Goal goal = goals.findById(userId, goalId).orElse(null);
        if (goal == null) return;

        List<String> order = goal.phaseOrder() == null ? List.of() : goal.phaseOrder();
        int idx = order.indexOf(phaseId);
        Optional<String> nextId = (idx >= 0 && idx + 1 < order.size())
            ? Optional.of(order.get(idx + 1))
            : Optional.empty();

        if (nextId.isPresent()) {
            Phase next = phases.findById(userId, goalId, nextId.get()).orElse(null);
            if (next != null && next.status() == PhaseStatus.LOCKED) {
                Phase activated = new Phase(
                    next.goalId(),
                    next.phaseId(),
                    next.title(),
                    next.description(),
                    next.orderIndex(),
                    PhaseStatus.ACTIVE,
                    next.targetStartDate(),
                    next.targetEndDate(),
                    next.completedAt(),
                    next.stepOrder()
                );
                phases.save(userId, activated);
            }
        } else {
            // No next Phase: complete the Goal. Sticky.
            if (goal.status() != GoalStatus.COMPLETED) {
                Goal done = new Goal(
                    goal.userId(),
                    goal.goalId(),
                    goal.title(),
                    goal.description(),
                    goal.domain(),
                    GoalStatus.COMPLETED,
                    goal.startDate(),
                    goal.targetDate(),
                    goal.createdAt(),
                    null,
                    now,
                    goal.phaseOrder(),
                    goal.source()
                );
                goals.save(done);
            }
        }
    }

    private static Goal withPhaseOrder(Goal g, List<String> order) {
        return new Goal(
            g.userId(),
            g.goalId(),
            g.title(),
            g.description(),
            g.domain(),
            g.status(),
            g.startDate(),
            g.targetDate(),
            g.createdAt(),
            null,
            g.completedAt(),
            order,
            g.source()
        );
    }

    private static Phase withStepOrder(Phase p, List<String> order) {
        return new Phase(
            p.goalId(),
            p.phaseId(),
            p.title(),
            p.description(),
            p.orderIndex(),
            p.status(),
            p.targetStartDate(),
            p.targetEndDate(),
            p.completedAt(),
            order
        );
    }
}
