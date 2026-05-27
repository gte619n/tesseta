package com.gte619n.healthfitness.core.goals.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalService;
import com.gte619n.healthfitness.core.goals.GoalSource;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseStatus;
import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepMetricBinding;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StepEvaluationService}.
 *
 * Uses in-memory repos plus a fake {@link MetricResolver} — no Spring
 * context, no Firestore.
 */
class StepEvaluationServiceTest {

    private static final String USER = "u1";
    private static final String GOAL = "g1";

    private InMemoryGoalRepository goals;
    private InMemoryPhaseRepository phases;
    private InMemoryStepRepository steps;
    private GoalService goalService;
    private FakeMetricResolver resolver;
    private StepEvaluationService evaluator;

    @BeforeEach
    void setUp() {
        goals = new InMemoryGoalRepository();
        phases = new InMemoryPhaseRepository();
        steps = new InMemoryStepRepository();
        goalService = new GoalService(goals, phases, steps);
        resolver = new FakeMetricResolver();
        evaluator = new StepEvaluationService(steps, phases, resolver, goalService);
    }

    @Test
    void thresholdLt_flipsUndoneToDone_whenValueBelowTarget() {
        // Goal with one Phase + one THRESHOLD Step bound to blood.ldl < 100.
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        resolver.put(USER, MetricKey.BLOOD_LDL, 87.0, Instant.now());

        evaluator.evaluateGoal(USER, GOAL);

        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertTrue(after.done(), "Step should auto-complete when value crosses target");
        assertFalse(after.manualOverride(), "manualOverride must stay false for auto flips");
        assertNotNull(after.doneAt(), "doneAt should be stamped on transition");
    }

    @Test
    void thresholdGt_flipsUndoneToDone_whenValueAboveTarget() {
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("body.leanMass", Comparator.GT, 60.0, null, null));
        resolver.put(USER, MetricKey.BODY_LEAN_MASS, 62.5, Instant.now());

        evaluator.evaluateGoal(USER, GOAL);

        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertTrue(after.done(), "Step should auto-complete when value exceeds target");
    }

    @Test
    void threshold_doesNotFlip_whenValueOnWrongSide() {
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        resolver.put(USER, MetricKey.BLOOD_LDL, 112.0, Instant.now());

        evaluator.evaluateGoal(USER, GOAL);

        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertFalse(after.done(), "Step should stay undone when threshold not crossed");
    }

    @Test
    void sustained_onlyFlips_whenSustainedHoldsReturnsTrue() {
        seedGoalWithSingleStep(StepKind.SUSTAINED,
            binding("vitals.restingHr", Comparator.LT, 55.0, 30, null));
        // Resolver value present but sustained window not yet satisfied.
        resolver.put(USER, MetricKey.VITALS_RESTING_HR, 52.0, Instant.now());
        resolver.putSustained(USER, MetricKey.VITALS_RESTING_HR, false);

        evaluator.evaluateGoal(USER, GOAL);
        Step intermediate = steps.findByGoal(USER, GOAL).get(0);
        assertFalse(intermediate.done(), "SUSTAINED stays undone until window holds");

        // Now flip the SUSTAINED check to true.
        resolver.putSustained(USER, MetricKey.VITALS_RESTING_HR, true);
        evaluator.evaluateGoal(USER, GOAL);
        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertTrue(after.done(), "SUSTAINED flips once sustainedHolds=true");
    }

    @Test
    void count_respectsCountFromTimestamp() {
        Instant countFrom = Instant.now().minusSeconds(3600);
        seedGoalWithSingleStep(StepKind.COUNT,
            binding("workouts.count", Comparator.GTE, 40.0, null, countFrom));
        resolver.putCount(USER, MetricKey.WORKOUTS_COUNT, 39L);

        evaluator.evaluateGoal(USER, GOAL);
        Step intermediate = steps.findByGoal(USER, GOAL).get(0);
        assertFalse(intermediate.done(), "COUNT not satisfied below target");

        resolver.putCount(USER, MetricKey.WORKOUTS_COUNT, 40L);
        evaluator.evaluateGoal(USER, GOAL);
        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertTrue(after.done(), "COUNT flips at >= target");
    }

    @Test
    void manualStep_isNeverAutoEvaluated() {
        seedGoalWithSingleStep(StepKind.MANUAL, null);
        // Even if a metric is staged with the (same-named) key, MANUAL ignores it.
        resolver.put(USER, MetricKey.BLOOD_LDL, 50.0, Instant.now());

        evaluator.evaluateGoal(USER, GOAL);

        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertFalse(after.done(), "MANUAL Steps never auto-flip");
    }

    @Test
    void manualOverride_skipsAutoEval_evenWhenConditionHolds() {
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        // Pre-set manualOverride.
        Step current = steps.findByGoal(USER, GOAL).get(0);
        Step override = new Step(
            current.goalId(), current.phaseId(), current.stepId(),
            current.title(), current.orderIndex(), current.kind(),
            false, null,
            true,                                  // manualOverride = true
            current.metric()
        );
        steps.save(USER, override);

        resolver.put(USER, MetricKey.BLOOD_LDL, 50.0, Instant.now());
        evaluator.evaluateGoal(USER, GOAL);

        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertFalse(after.done(), "manualOverride freezes the Step against auto-eval");
        assertTrue(after.manualOverride(), "manualOverride flag persists");
    }

    @Test
    void doneStep_metricRegressed_staysDone_andFlagsRegression() {
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        // Auto-complete on a good reading first.
        resolver.put(USER, MetricKey.BLOOD_LDL, 87.0, Instant.now());
        evaluator.evaluateGoal(USER, GOAL);
        Step done = steps.findByGoal(USER, GOAL).get(0);
        assertTrue(done.done());

        // Now regress the metric across the target.
        resolver.put(USER, MetricKey.BLOOD_LDL, 115.0, Instant.now());

        EvaluationResult result = evaluator.evaluateStep(USER, done);
        assertTrue(result.regressed(), "regressed flag is true when value crosses back over target");
        assertFalse(result.changed(), "no transition on regression — Step stays done");

        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertTrue(after.done(), "done=true is sticky — never auto-undone");
        assertNotNull(after.doneAt(), "doneAt remains stamped");
        assertTrue(evaluator.computeRegressionFlag(USER, after), "regression flag computed on read");
    }

    @Test
    void doneStep_metricStillHolds_noRegression() {
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        resolver.put(USER, MetricKey.BLOOD_LDL, 87.0, Instant.now());
        evaluator.evaluateGoal(USER, GOAL);
        Step done = steps.findByGoal(USER, GOAL).get(0);

        // Metric drifts but still on the good side of the target.
        resolver.put(USER, MetricKey.BLOOD_LDL, 92.0, Instant.now());
        assertFalse(evaluator.computeRegressionFlag(USER, done),
            "no regression when metric still satisfies binding");
    }

    @Test
    void phaseCascade_allStepsDone_advancesNextPhase() {
        // Two phases, one step each. Both THRESHOLD bound to blood.ldl.
        seedGoal();
        String p1 = seedPhase("p1", 0, PhaseStatus.ACTIVE);
        String p2 = seedPhase("p2", 1, PhaseStatus.LOCKED);
        appendPhaseOrder(p1, p2);
        seedStep(p1, "s1",
            StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        seedStep(p2, "s2",
            StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 80.0, null, null));

        resolver.put(USER, MetricKey.BLOOD_LDL, 87.0, Instant.now());
        evaluator.evaluateGoal(USER, GOAL);

        Phase phase1 = phases.findById(USER, GOAL, p1).orElseThrow();
        Phase phase2 = phases.findById(USER, GOAL, p2).orElseThrow();
        assertEquals(PhaseStatus.COMPLETED, phase1.status(), "phase 1 should complete");
        assertEquals(PhaseStatus.ACTIVE, phase2.status(), "phase 2 should activate");
        assertNotNull(phase1.completedAt());
    }

    @Test
    void goalCascade_lastPhaseDone_completesGoal() {
        // Single Phase, single Step — completing it should complete the Goal.
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));

        resolver.put(USER, MetricKey.BLOOD_LDL, 87.0, Instant.now());
        evaluator.evaluateGoal(USER, GOAL);

        Goal after = goals.findById(USER, GOAL).orElseThrow();
        assertEquals(GoalStatus.COMPLETED, after.status(), "Goal should complete");
        assertNotNull(after.completedAt(), "completedAt should be stamped");
    }

    @Test
    void stickyRegression_completedGoalStaysCompleted_whenMetricRegresses() {
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        // Complete the Goal.
        resolver.put(USER, MetricKey.BLOOD_LDL, 87.0, Instant.now());
        evaluator.evaluateGoal(USER, GOAL);
        assertEquals(GoalStatus.COMPLETED, goals.findById(USER, GOAL).orElseThrow().status());

        // Regress the metric — Step stays done, Goal stays completed.
        resolver.put(USER, MetricKey.BLOOD_LDL, 115.0, Instant.now());
        evaluator.evaluateGoal(USER, GOAL);

        Step step = steps.findByGoal(USER, GOAL).get(0);
        assertTrue(step.done(), "done is sticky against regression");
        Goal goal = goals.findById(USER, GOAL).orElseThrow();
        assertEquals(GoalStatus.COMPLETED, goal.status(), "Goal completion is sticky");
    }

    @Test
    void stickyManualUnwind_phaseStaysCompleted_afterManualOverride() {
        // Two Steps in one Phase; complete both → Phase + Goal completed.
        // Then user manually un-checks one Step (sets manualOverride=true).
        // The Phase must remain COMPLETED (sticky).
        seedGoal();
        String p1 = seedPhase("p1", 0, PhaseStatus.ACTIVE);
        appendPhaseOrder(p1);
        seedStep(p1, "s1", StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        seedStep(p1, "s2", StepKind.THRESHOLD,
            binding("blood.apoB", Comparator.LT, 80.0, null, null));

        resolver.put(USER, MetricKey.BLOOD_LDL, 87.0, Instant.now());
        resolver.put(USER, MetricKey.BLOOD_APOB, 70.0, Instant.now());
        evaluator.evaluateGoal(USER, GOAL);

        Phase completed = phases.findById(USER, GOAL, p1).orElseThrow();
        assertEquals(PhaseStatus.COMPLETED, completed.status());

        // User manually un-checks s1 — GoalService.markStepDone(done=false, override=true).
        goalService.markStepDone(USER, GOAL, p1, "s1", false, true);

        Phase after = phases.findById(USER, GOAL, p1).orElseThrow();
        assertEquals(PhaseStatus.COMPLETED, after.status(),
            "Phase completion is sticky — manual unwind does not reopen the Phase");
        Goal goal = goals.findById(USER, GOAL).orElseThrow();
        assertEquals(GoalStatus.COMPLETED, goal.status(),
            "Goal completion is sticky — manual unwind does not reopen the Goal");

        // The Step itself records the user's intent.
        Step s1 = steps.findById(USER, GOAL, p1, "s1").orElseThrow();
        assertFalse(s1.done(), "manual unwind sets done=false on the Step");
        assertTrue(s1.manualOverride(), "manualOverride is set");

        // Now even with the metric still satisfied, auto-eval must NOT
        // re-flip the Step (manualOverride freezes it).
        evaluator.evaluateGoal(USER, GOAL);
        Step refreshed = steps.findById(USER, GOAL, p1, "s1").orElseThrow();
        assertFalse(refreshed.done(),
            "manualOverride freezes the Step against re-flip");
    }

    @Test
    void onMetricChanged_reEvaluatesOnlyBoundSteps() {
        // Goal with one Step bound to blood.ldl. Stage a value.
        seedGoalWithSingleStep(StepKind.THRESHOLD,
            binding("blood.ldl", Comparator.LT, 100.0, null, null));
        resolver.put(USER, MetricKey.BLOOD_LDL, 87.0, Instant.now());

        evaluator.onMetricChanged(USER, MetricKey.BLOOD_LDL);

        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertTrue(after.done(), "onMetricChanged should flip the bound Step");
    }

    @Test
    void unknownMetricKey_isNoOp_notACrash() {
        seedGoal();
        String p1 = seedPhase("p1", 0, PhaseStatus.ACTIVE);
        appendPhaseOrder(p1);
        // Binding carries a stale/unknown key.
        seedStep(p1, "s1", StepKind.THRESHOLD,
            binding("definitely.not.a.real.metric", Comparator.LT, 100.0, null, null));

        // No throw, no flip.
        evaluator.evaluateGoal(USER, GOAL);
        Step after = steps.findByGoal(USER, GOAL).get(0);
        assertFalse(after.done(), "unknown metric key leaves Step alone");
    }

    @Test
    void reevaluateAllSustained_isStubInPhase3() {
        // Just exercise the method — Phase 3 is a no-op stub. Should not throw.
        evaluator.reevaluateAllSustained();
    }

    // ---------- fixtures ----------

    /** Build the canonical 1-phase 1-step Goal. */
    private void seedGoalWithSingleStep(StepKind kind, StepMetricBinding metric) {
        seedGoal();
        String p1 = seedPhase("p1", 0, PhaseStatus.ACTIVE);
        appendPhaseOrder(p1);
        seedStep(p1, "s1", kind, metric);
    }

    private void seedGoal() {
        goals.save(new Goal(
            USER, GOAL,
            "Test goal", "desc",
            GoalDomain.CARDIOVASCULAR, GoalStatus.ACTIVE,
            LocalDate.now(), LocalDate.now().plusMonths(6),
            Instant.now(), Instant.now(), null,
            List.of(),
            GoalSource.MANUAL
        ));
    }

    private String seedPhase(String phaseId, int orderIndex, PhaseStatus status) {
        phases.save(USER, new Phase(
            GOAL, phaseId,
            "Phase " + orderIndex, "desc",
            orderIndex, status,
            LocalDate.now(), LocalDate.now().plusMonths(2),
            null, List.of()
        ));
        return phaseId;
    }

    private void appendPhaseOrder(String... phaseIds) {
        Goal g = goals.findById(USER, GOAL).orElseThrow();
        Goal updated = new Goal(
            g.userId(), g.goalId(), g.title(), g.description(),
            g.domain(), g.status(),
            g.startDate(), g.targetDate(),
            g.createdAt(), g.updatedAt(), g.completedAt(),
            List.of(phaseIds),
            g.source()
        );
        goals.save(updated);
    }

    private void seedStep(String phaseId, String stepId, StepKind kind, StepMetricBinding metric) {
        steps.save(USER, new Step(
            GOAL, phaseId, stepId,
            "Step " + stepId, stepOrder(phaseId),
            kind,
            false, null,
            false,
            metric
        ));
        // Append to phase's stepOrder so cascade walks correctly.
        Phase p = phases.findById(USER, GOAL, phaseId).orElseThrow();
        java.util.List<String> order = new java.util.ArrayList<>(p.stepOrder() == null ? List.of() : p.stepOrder());
        order.add(stepId);
        phases.save(USER, new Phase(
            p.goalId(), p.phaseId(), p.title(), p.description(),
            p.orderIndex(), p.status(),
            p.targetStartDate(), p.targetEndDate(),
            p.completedAt(), order
        ));
    }

    private int stepOrder(String phaseId) {
        return steps.findByPhase(USER, GOAL, phaseId).size();
    }

    private static StepMetricBinding binding(
        String metricKey,
        Comparator cmp,
        double targetValue,
        Integer windowDays,
        Instant countFrom
    ) {
        return new StepMetricBinding(metricKey, cmp, targetValue, windowDays, countFrom);
    }
}
