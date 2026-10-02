package com.gte619n.healthfitness.core.adhoc.catalog;

import com.gte619n.healthfitness.core.catalog.CatalogProvenance;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.time.Instant;
import java.util.List;

/**
 * IMPL-MULTIUSER-01 P3.3 (D9) — shared, app-wide, template-only copy of a user's
 * {@code AdHocWorkout}, stored at top-level {@code adhocCatalog/{catalogId}}.
 *
 * <p>Per D9 this is a <b>copy</b> with per-user fields stripped: no
 * {@code userId}, no run counters ({@code runCount}/{@code lastPerformedAt}),
 * no {@code equipmentContext} binding (a user's gym constraint), no original
 * prompt. The {@code AdHocWorkout} entity has no native status today, so the
 * catalog lifecycle lives entirely on this copy (spec §P3.3). The embedded
 * {@link WorkoutDay} body is generalized by {@code ProgramGeneralizer} (loads→%,
 * notes/ids stripped) before storage.
 */
public record CatalogAdHocWorkout(
    String catalogId,
    String title,
    String summary,
    List<String> tags,
    WorkoutDay day,
    Integer targetDurationMinutes,
    Integer estimatedDurationSeconds,
    CatalogStatus status,
    CatalogProvenance provenance,
    Instant createdAt,
    Instant updatedAt
) {
    public CatalogAdHocWorkout {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
