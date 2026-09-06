package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * The week loop's output (IMPL-PROG-01 §7): the target weekly set count per
 * movement pattern and which patterns are currently on a deload. Read by the
 * session loop only to know how many sets to prescribe; load stays the session
 * loop's alone.
 */
public record WeekParameters(
    String userId,
    Map<MovementPattern, Integer> weeklySetTargetByPattern,
    Set<MovementPattern> deloadActiveForPatterns,
    Instant computedAt
) {
    public int weeklySetTarget(MovementPattern pattern, int fallback) {
        Integer t = weeklySetTargetByPattern == null ? null : weeklySetTargetByPattern.get(pattern);
        return t != null ? t : fallback;
    }

    public boolean isDeloadActive(MovementPattern pattern) {
        return deloadActiveForPatterns != null && deloadActiveForPatterns.contains(pattern);
    }
}
