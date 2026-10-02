package com.gte619n.healthfitness.api.catalog;

import com.gte619n.healthfitness.core.adhoc.catalog.CatalogAdHocWorkout;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.time.Instant;
import java.util.List;

/**
 * Public, non-admin view of a shared ad-hoc template (IMPL-MULTIUSER-01 P3.3).
 * Omits provenance (D12 internal-only). The body is the generalized day.
 */
public record CatalogAdHocResponse(
    String catalogId,
    String title,
    String summary,
    List<String> tags,
    CatalogStatus status,
    WorkoutDay day,
    Integer targetDurationMinutes,
    Integer estimatedDurationSeconds,
    Instant createdAt,
    Instant updatedAt
) {
    public static CatalogAdHocResponse from(CatalogAdHocWorkout c) {
        return new CatalogAdHocResponse(
            c.catalogId(), c.title(), c.summary(), c.tags(), c.status(), c.day(),
            c.targetDurationMinutes(), c.estimatedDurationSeconds(),
            c.createdAt(), c.updatedAt());
    }
}
