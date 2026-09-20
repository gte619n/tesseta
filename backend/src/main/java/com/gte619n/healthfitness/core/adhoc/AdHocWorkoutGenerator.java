package com.gte619n.healthfitness.core.adhoc;

import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.util.List;
import java.util.Set;

/**
 * Generates a single ad-hoc workout from a natural-language prompt within a time
 * budget and an equipment allow-list (IMPL-ADHOC-01 D15). The dedicated
 * single-day counterpart to the multi-phase program designer; the Gemini
 * implementation is gated behind {@code app.adhoc-workouts.enabled}.
 */
public interface AdHocWorkoutGenerator {

    /** The generated workout plus AI-suggested naming/tags. */
    record Generated(String title, String summary, List<String> tags, WorkoutDay day) {}

    /**
     * @param userId                 owner (for history-grounded loads)
     * @param prompt                 the user's request ("30 min hotel gym")
     * @param targetDurationMinutes  requested budget, or null
     * @param availableEquipmentIds  the resolved hard equipment allow-list; only
     *                               exercises executable with this gear may be
     *                               prescribed
     */
    Generated generate(
        String userId, String prompt, Integer targetDurationMinutes,
        Set<String> availableEquipmentIds);
}
