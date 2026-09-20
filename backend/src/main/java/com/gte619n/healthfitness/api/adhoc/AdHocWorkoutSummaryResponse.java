package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.core.adhoc.AdHocSource;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import java.time.Instant;
import java.util.List;

/**
 * Shallow library-list item — no workout body, so a list read resolves no
 * exercise summaries (IMPL-ADHOC-01 D7, list speed).
 */
public record AdHocWorkoutSummaryResponse(
    String adhocId,
    String title,
    String summary,
    AdHocSource source,
    EquipmentContextDto equipmentContext,
    Integer targetDurationMinutes,
    Integer estimatedDurationSeconds,
    List<String> tags,
    boolean pinned,
    int runCount,
    Instant lastPerformedAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static AdHocWorkoutSummaryResponse from(AdHocWorkout w) {
        return new AdHocWorkoutSummaryResponse(
            w.adhocId(), w.title(), w.summary(), w.source(),
            EquipmentContextDto.from(w.equipmentContext()),
            w.targetDurationMinutes(), w.estimatedDurationSeconds(),
            w.tags(), w.pinned(), w.runCount(), w.lastPerformedAt(),
            w.createdAt(), w.updatedAt());
    }
}
