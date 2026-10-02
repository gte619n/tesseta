package com.gte619n.healthfitness.core.workoutprogram.catalog;

import com.gte619n.healthfitness.core.catalog.CatalogProvenance;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import java.time.Instant;
import java.util.List;

/**
 * IMPL-MULTIUSER-01 P3.2 (D9/D10) — a shared, app-wide, template-only copy of a
 * user's {@code WorkoutProgram}, stored at top-level {@code programCatalog/{catalogId}}.
 *
 * <p>This is a <b>copy</b> (D9 — not promote-in-place): per-user identifiers are
 * deliberately STRIPPED. There is no {@code userId}, {@code startDate},
 * {@code goalId}, or {@code completedAt} — a catalog template is a reusable
 * blueprint, not a scheduled instance. The phase tree is carried through
 * {@link ProgramGeneralizer} first, which converts absolute loads to relative %
 * and strips notes/goal links/identifiers (D10).
 *
 * <p>{@code status}/{@code provenance} are the shared catalog lifecycle (D8/D12).
 */
public record CatalogWorkoutProgram(
    String catalogId,
    String title,
    String description,
    ProgramSchedule schedule,        // training-day/gym template (gym ids are user-specific but harmless; see generalizer)
    List<String> phaseOrder,
    List<ProgramPhase> phases,       // generalized: %1RM intensities, no notes/ids beyond exerciseId
    CatalogStatus status,
    CatalogProvenance provenance,
    Instant createdAt,
    Instant updatedAt
) {}
