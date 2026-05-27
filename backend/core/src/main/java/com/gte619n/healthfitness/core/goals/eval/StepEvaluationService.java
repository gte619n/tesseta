package com.gte619n.healthfitness.core.goals.eval;

import com.gte619n.healthfitness.core.goals.GoalService;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepMetricBinding;
import com.gte619n.healthfitness.core.goals.StepRepository;
import com.gte619n.healthfitness.core.goals.events.MetricChangedEvent;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The Goals state-machine's auto-evaluator.
 *
 * <h2>The notify-never-undo policy</h2>
 *
 * Auto-evaluation is a one-way ratchet: it can flip a Step from
 * undone → done, but it <strong>never</strong> flips a Step
 * done → undone. If a metric later regresses across a done Step's
 * target, the Step stays done and the regression is surfaced as a
 * transient flag on the response DTO, not as a write to the Step.
 *
 * Other invariants enforced here:
 *
 * <ul>
 *   <li>{@link StepKind#MANUAL} Steps are never auto-evaluated.</li>
 *   <li>A Step with {@code manualOverride = true} is never
 *       auto-evaluated — the user's hand wins until they tap
 *       "Reset to auto" via PATCH.</li>
 *   <li>All transitions go through {@link GoalService#markStepDone}
 *       so that phase and goal progression cascades fire from a single
 *       place.</li>
 *   <li>Phase and Goal completion are sticky — they never reverse
 *       (enforced by {@link GoalService}).</li>
 * </ul>
 */
@Service
public class StepEvaluationService {

    private static final Logger log = System.getLogger(StepEvaluationService.class.getName());

    private final StepRepository steps;
    private final PhaseRepository phases;
    private final MetricResolver resolver;
    private final GoalService goalService;
    private final UserRepository users;

    public StepEvaluationService(
        StepRepository steps,
        PhaseRepository phases,
        MetricResolver resolver,
        GoalService goalService,
        UserRepository users
    ) {
        this.steps = steps;
        this.phases = phases;
        this.resolver = resolver;
        this.goalService = goalService;
        this.users = users;
    }

    /**
     * Spring event listener — dispatched synchronously on the writer's
     * thread after the save completes.
     *
     * The try/catch is load-bearing: a Goals bug must NEVER propagate
     * back to the writing module (BloodController, AdherenceController,
     * etc.). If the listener fails, we log and return — the writer's
     * response is unaffected.
     */
    @EventListener
    public void on(MetricChangedEvent event) {
        try {
            MetricKey key = MetricKey.fromKey(event.metricKey());
            if (key == null) return;
            onMetricChanged(event.userId(), key);
        } catch (RuntimeException ex) {
            log.log(Level.WARNING,
                "MetricChanged listener failed for key=" + event.metricKey()
                    + " user=" + event.userId(), ex);
        }
    }

    /**
     * Re-evaluate every Step bound to a given metric key for one user.
     * Driven by metric-write event listeners in Phase 4.
     */
    public void onMetricChanged(String userId, MetricKey key) {
        if (userId == null || key == null) return;
        List<Step> bound = steps.findByMetricKey(userId, key.key());
        for (Step s : bound) {
            evaluateAndApply(userId, s);
        }
    }

    /**
     * Re-evaluate every Step in one Goal. Driven by
     * {@code GET /api/me/goals/{id}} and the explicit reevaluate POST.
     */
    public void evaluateGoal(String userId, String goalId) {
        if (userId == null || goalId == null) return;
        List<Step> all = steps.findByGoal(userId, goalId);
        for (Step s : all) {
            evaluateAndApply(userId, s);
        }
    }

    /**
     * Re-evaluate every SUSTAINED Step across all users.
     *
     * Driven by the daily Cloud Run Job ({@code ReevaluateSustainedJob}
     * under {@code @Profile("job-sustained")}, IMPL-12 Phase 5). Iterates
     * every user, then every SUSTAINED Step for that user, and applies
     * the standard {@link #evaluateAndApply} flow. Each user's loop is
     * wrapped in try/catch so one corrupt user can't kill the whole run.
     */
    public void reevaluateAllSustained() {
        List<String> userIds = users.findAllUserIds();
        log.log(Level.INFO,
            "reevaluateAllSustained: starting for " + userIds.size() + " users");
        int evaluated = 0;
        int userErrors = 0;
        for (String userId : userIds) {
            try {
                List<Step> sustained = steps.findAllSustained(userId);
                for (Step s : sustained) {
                    evaluateAndApply(userId, s);
                    evaluated++;
                }
            } catch (RuntimeException ex) {
                userErrors++;
                log.log(Level.WARNING,
                    "reevaluateAllSustained: user " + userId + " failed — skipping",
                    ex);
            }
        }
        log.log(Level.INFO,
            "reevaluateAllSustained: done — " + evaluated + " steps evaluated, "
                + userErrors + " user errors");
    }

    /**
     * Compute the regression flag for a Step on read (controller's
     * response mapper calls this).
     *
     * Returns true iff the Step is currently done AND the current
     * metric reading no longer satisfies the binding. Returns false
     * for: MANUAL Steps, undone Steps, manualOverride Steps, Steps
     * with no binding, Steps whose metric key isn't in the registry,
     * and Steps whose metric is unavailable.
     */
    public boolean computeRegressionFlag(String userId, Step step) {
        if (step == null) return false;
        if (!step.done()) return false;
        if (step.kind() == StepKind.MANUAL) return false;
        if (step.manualOverride()) return false;
        StepMetricBinding b = step.metric();
        if (b == null) return false;
        MetricKey key = MetricKey.fromKey(b.metricKey());
        if (key == null) return false;
        return !conditionHolds(userId, step, key, b);
    }

    /** Evaluate one Step and persist any state changes. */
    EvaluationResult evaluateAndApply(String userId, Step step) {
        EvaluationResult result = evaluateStep(userId, step);
        return result;
    }

    /**
     * Per-Step core evaluation.
     *
     * Side effect: when an undone non-MANUAL non-override Step's
     * condition now holds, this delegates to
     * {@link GoalService#markStepDone} which writes the new state and
     * cascades phase/goal completion.
     */
    EvaluationResult evaluateStep(String userId, Step step) {
        if (step == null) return EvaluationResult.noChange(null);
        // MANUAL Steps are never auto-evaluated.
        if (step.kind() == StepKind.MANUAL) return EvaluationResult.noChange(step);
        // The user's hand wins until they reset to auto.
        if (step.manualOverride()) return EvaluationResult.noChange(step);

        StepMetricBinding b = step.metric();
        if (b == null) {
            // Defensive: a non-MANUAL Step without a binding can't be
            // auto-evaluated. Treat as no-op rather than crash.
            return EvaluationResult.noChange(step);
        }

        MetricKey key = MetricKey.fromKey(b.metricKey());
        if (key == null) {
            // Stale/unknown metric key on the binding — degrade
            // silently rather than crash. The Step stays in whatever
            // state it's currently in.
            return EvaluationResult.noChange(step);
        }

        boolean holds;
        try {
            holds = conditionHolds(userId, step, key, b);
        } catch (RuntimeException e) {
            // Resolver failures must never break Step evaluation for
            // other Steps. Log and move on.
            log.log(Level.WARNING,
                () -> "conditionHolds threw for userId=" + userId
                    + " stepId=" + step.stepId()
                    + " key=" + b.metricKey() + " — skipping",
                e);
            return EvaluationResult.noChange(step);
        }

        if (step.done()) {
            // Notify, never auto-undo — only check for regression.
            // This is the load-bearing line; see class javadoc.
            return new EvaluationResult(step, false, !holds);
        }

        if (holds) {
            // Undone → done. Route through GoalService so phase/goal
            // progression cascades fire from one place.
            // manualOverride stays false: this transition is automatic.
            Step updated = goalService.markStepDone(
                userId,
                step.goalId(),
                step.phaseId(),
                step.stepId(),
                true,
                false
            );
            return new EvaluationResult(updated, true, false);
        }

        return EvaluationResult.noChange(step);
    }

    /**
     * Visible to {@link #computeRegressionFlag} and {@link #evaluateStep}.
     * Pre-conditions: kind != MANUAL, manualOverride == false, binding
     * != null, key != null.
     */
    private boolean conditionHolds(
        String userId,
        Step step,
        MetricKey key,
        StepMetricBinding b
    ) {
        return switch (step.kind()) {
            case THRESHOLD -> {
                MetricValue v = resolver.resolve(userId, key);
                if (!v.isAvailable()) yield false;
                yield FirestoreMetricResolver.compare(v.value().get(), b.comparator(), b.targetValue());
            }
            case SUSTAINED -> {
                int windowDays = b.windowDays() != null ? b.windowDays() : 0;
                yield resolver.sustainedHolds(userId, key, b.comparator(), b.targetValue(), windowDays);
            }
            case COUNT -> {
                long count = resolver.countSince(userId, key, b.countFrom());
                // For COUNT the target is the threshold count;
                // comparator semantics fall back to GTE since the spec
                // describes count as "reached a target".
                yield count >= (long) b.targetValue();
            }
            // MANUAL is filtered out earlier; this branch is just to
            // satisfy exhaustiveness.
            case MANUAL -> false;
        };
    }

    // ----- accessors used by tests + controller wiring -----

    /** Visible for the controller's reevaluate endpoint via Phase wiring. */
    StepRepository steps() { return steps; }
    PhaseRepository phases() { return phases; }
}
