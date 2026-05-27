package com.gte619n.healthfitness.core.goals.eval;

import com.gte619n.healthfitness.core.goals.Step;

/**
 * Outcome of evaluating a single Step.
 *
 * {@code step} is the Step the evaluator saw — possibly the updated
 * version after a transition.
 *
 * {@code changed} is true iff this evaluation flipped the Step
 * undone → done. By the notify-never-undo policy, the evaluator never
 * flips done → undone, so {@code changed} is one-directional.
 *
 * {@code regressed} is true iff the Step is currently done AND the
 * latest metric read no longer satisfies the binding. Transient — not
 * persisted on the Step, only surfaced on response DTOs.
 */
public record EvaluationResult(Step step, boolean changed, boolean regressed) {

    /** Convenience: no transition and no regression. */
    public static EvaluationResult noChange(Step step) {
        return new EvaluationResult(step, false, false);
    }
}
