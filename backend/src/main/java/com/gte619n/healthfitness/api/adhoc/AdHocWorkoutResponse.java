package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.DayResponse;
import com.gte619n.healthfitness.core.adhoc.AdHocSource;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import java.time.Instant;
import java.util.List;

/** Full ad-hoc workout template with the resolved workout body (IMPL-ADHOC-01). */
public record AdHocWorkoutResponse(
    String adhocId,
    String title,
    String summary,
    AdHocSource source,
    String prompt,
    EquipmentContextDto equipmentContext,
    Integer targetDurationMinutes,
    Integer estimatedDurationSeconds,
    List<String> tags,
    boolean pinned,
    DayResponse day,
    int runCount,
    Instant lastPerformedAt,
    Instant createdAt,
    Instant updatedAt
) {
    /** Build from a template + its already-resolved day response. */
    public static AdHocWorkoutResponse from(AdHocWorkout w, DayResponse day) {
        return new AdHocWorkoutResponse(
            w.adhocId(), w.title(), w.summary(), w.source(), w.prompt(),
            EquipmentContextDto.from(w.equipmentContext()),
            w.targetDurationMinutes(), w.estimatedDurationSeconds(),
            w.tags(), w.pinned(), day, w.runCount(), w.lastPerformedAt(),
            w.createdAt(), w.updatedAt());
    }
}
