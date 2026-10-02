package com.gte619n.healthfitness.api.catalog;

import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.catalog.CatalogWorkoutProgram;
import java.time.Instant;
import java.util.List;

/**
 * Public, non-admin view of a shared program template (IMPL-MULTIUSER-01 P3.2).
 *
 * <p><b>Deliberately omits {@code CatalogProvenance}</b> — contributor credit is
 * internal-only (D12) and must never reach a non-admin reader. The phase tree is
 * passed through as-is (already generalized: %1RM intensities, no notes/ids).
 */
public record CatalogProgramResponse(
    String catalogId,
    String title,
    String description,
    CatalogStatus status,
    List<String> phaseOrder,
    List<ProgramPhase> phases,
    Instant createdAt,
    Instant updatedAt
) {
    public static CatalogProgramResponse from(CatalogWorkoutProgram c) {
        return new CatalogProgramResponse(
            c.catalogId(), c.title(), c.description(), c.status(),
            c.phaseOrder(), c.phases(), c.createdAt(), c.updatedAt());
    }
}
