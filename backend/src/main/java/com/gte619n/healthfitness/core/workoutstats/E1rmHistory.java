package com.gte619n.healthfitness.core.workoutstats;

import java.time.LocalDate;
import java.util.List;

/**
 * The estimated-1RM curve for one exercise (IMPL-WEB-WORKOUT-01 §5.3): one point
 * per session it was performed, derived by Epley {@code w×(1+reps/30)} on the
 * session's best set. The historical curve is an estimate (hence the label web
 * shows); {@code currentBelief} is the engine's authoritative Kalman number and
 * is null when the exercise has no progression state yet.
 *
 * @param exerciseId    the exercise
 * @param exerciseName  its display name (falls back to the id)
 * @param points        chronological (oldest→newest); empty when no history
 * @param currentBelief the engine's current e1RM belief, or null
 */
public record E1rmHistory(
    String exerciseId,
    String exerciseName,
    List<Point> points,
    Belief currentBelief
) {

    /**
     * @param date          the session date
     * @param e1rmLbs       Epley estimate on the best set (or the weight itself
     *                      for a weight-only imported row)
     * @param weightLbs     the best set's weight
     * @param reps          the best set's reps, null for a weight-only row
     * @param lowConfidence true when derived from a weight-only row (reps null)
     */
    public record Point(LocalDate date, double e1rmLbs, Double weightLbs, Integer reps, boolean lowConfidence) {}

    /** The engine's current belief: point estimate, uncertainty, confidence band. */
    public record Belief(double e1rmLbs, double sigmaLbs, String confidence) {}
}
