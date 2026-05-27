package com.gte619n.healthfitness.core.goals.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.gte619n.healthfitness.core.user.User;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link StepEvaluationService#reevaluateAllSustained()}
 * iterates every user from {@link com.gte619n.healthfitness.core.user.UserRepository}
 * and considers every SUSTAINED Step for that user.
 *
 * Uses a recording {@link MetricResolver} subclass so the test can
 * assert which (userId, key) pairs were probed during the run.
 */
class SustainedReevalIterationTest {

    private InMemoryGoalRepository goals;
    private InMemoryPhaseRepository phases;
    private InMemoryStepRepository steps;
    private InMemoryUserRepository users;
    private GoalService goalService;
    private RecordingFakeMetricResolver resolver;
    private StepEvaluationService evaluator;

    @BeforeEach
    void setUp() {
        goals = new InMemoryGoalRepository();
        phases = new InMemoryPhaseRepository();
        steps = new InMemoryStepRepository();
        users = new InMemoryUserRepository();
        goalService = new GoalService(goals, phases, steps);
        resolver = new RecordingFakeMetricResolver();
        evaluator = new StepEvaluationService(steps, phases, resolver, goalService, users);
    }

    @Test
    void reevaluateAllSustained_visitsEverySustainedStep_acrossAllUsers() {
        // Two users, each with one SUSTAINED Step bound to vitals.restingHr.
        seedUserWithSustainedStep("alice", "goal-a", "phase-a", "step-a",
            MetricKey.VITALS_RESTING_HR);
        seedUserWithSustainedStep("bob", "goal-b", "phase-b", "step-b",
            MetricKey.VITALS_HRV);
        // Stage a SUSTAINED "false" answer so nothing flips; the test
        // cares about whether the resolver was *invoked* per user.
        resolver.putSustained("alice", MetricKey.VITALS_RESTING_HR, false);
        resolver.putSustained("bob", MetricKey.VITALS_HRV, false);

        evaluator.reevaluateAllSustained();

        assertTrue(resolver.invocations.contains("alice|vitals.restingHr"),
            "alice's SUSTAINED Step should have been re-evaluated");
        assertTrue(resolver.invocations.contains("bob|vitals.hrv"),
            "bob's SUSTAINED Step should have been re-evaluated");
        assertEquals(2, resolver.invocations.size(),
            "exactly one sustainedHolds call per (user, key)");
    }

    @Test
    void reevaluateAllSustained_skipsNonSustainedSteps() {
        // alice has both a SUSTAINED and a THRESHOLD Step — only the
        // SUSTAINED one should be probed by the daily pass.
        seedUserWithSustainedStep("alice", "goal-a", "phase-a", "sustainedStep",
            MetricKey.VITALS_RESTING_HR);
        // Add a THRESHOLD Step in the same phase.
        steps.save("alice", new Step(
            "goal-a", "phase-a", "thresholdStep",
            "Threshold Step", 1, StepKind.THRESHOLD,
            false, null,
            false,
            new StepMetricBinding("blood.ldl", Comparator.LT, 100.0, null, null)
        ));
        resolver.putSustained("alice", MetricKey.VITALS_RESTING_HR, false);

        evaluator.reevaluateAllSustained();

        assertTrue(resolver.invocations.contains("alice|vitals.restingHr"));
        assertEquals(1, resolver.invocations.size(),
            "THRESHOLD Steps are not part of the daily SUSTAINED pass");
    }

    @Test
    void reevaluateAllSustained_oneUserFailing_doesNotKillTheRun() {
        seedUserWithSustainedStep("alice", "goal-a", "phase-a", "step-a",
            MetricKey.VITALS_RESTING_HR);
        seedUserWithSustainedStep("bob", "goal-b", "phase-b", "step-b",
            MetricKey.VITALS_HRV);
        // Make alice's resolver call throw; bob should still be visited.
        resolver.throwingFor.add("alice|vitals.restingHr");
        resolver.putSustained("bob", MetricKey.VITALS_HRV, false);

        evaluator.reevaluateAllSustained();

        assertTrue(resolver.invocations.contains("bob|vitals.hrv"),
            "bob must still be visited even though alice threw");
    }

    // ---------- fixtures ----------

    private void seedUserWithSustainedStep(
        String userId,
        String goalId,
        String phaseId,
        String stepId,
        MetricKey key
    ) {
        users.save(new User(
            userId, userId + "@test", userId,
            null, null, Instant.now(), Instant.now()
        ));
        goals.save(new Goal(
            userId, goalId, "Goal " + userId, "desc",
            GoalDomain.CARDIOVASCULAR, GoalStatus.ACTIVE,
            LocalDate.now(), LocalDate.now().plusMonths(6),
            Instant.now(), Instant.now(), null,
            List.of(phaseId),
            GoalSource.MANUAL
        ));
        phases.save(userId, new Phase(
            goalId, phaseId, "Phase 0", "desc",
            0, PhaseStatus.ACTIVE,
            LocalDate.now(), LocalDate.now().plusMonths(2),
            null, new ArrayList<>(List.of(stepId))
        ));
        steps.save(userId, new Step(
            goalId, phaseId, stepId,
            "Sustained step", 0, StepKind.SUSTAINED,
            false, null,
            false,
            new StepMetricBinding(key.key(), Comparator.LT, 55.0, 30, null)
        ));
    }

    /**
     * MetricResolver that records every {@code sustainedHolds} call as
     * {@code "<userId>|<metricKey>"} so the test can assert which
     * (user, key) pairs were exercised. Optionally throws for selected
     * keys to simulate a per-user failure.
     */
    private static final class RecordingFakeMetricResolver implements MetricResolver {
        final Set<String> invocations = new HashSet<>();
        final Set<String> throwingFor = new HashSet<>();
        private final java.util.Map<String, Boolean> sustained = new java.util.HashMap<>();

        void putSustained(String userId, MetricKey key, boolean holds) {
            sustained.put(userId + "|" + key.key(), holds);
        }

        @Override
        public MetricValue resolve(String userId, MetricKey key) {
            return MetricValue.unavailable();
        }

        @Override
        public boolean sustainedHolds(String userId, MetricKey key,
            Comparator cmp, double target, int windowDays) {
            String k = userId + "|" + key.key();
            invocations.add(k);
            if (throwingFor.contains(k)) {
                throw new RuntimeException("simulated resolver failure for " + k);
            }
            return sustained.getOrDefault(k, Boolean.FALSE);
        }

        @Override
        public long countSince(String userId, MetricKey key, Instant from) {
            return 0L;
        }
    }
}
