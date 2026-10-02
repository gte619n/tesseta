package com.gte619n.healthfitness.core.catalog;

import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;

/**
 * IMPL-MULTIUSER-01 P3.1 (D8) — pure, lossless-where-possible mapping between
 * each entity's native stored enum and the shared {@link CatalogStatus}
 * vocabulary. We MAP, we do NOT migrate (D8): the physical enums stay exactly
 * as they are on disk; this class is the only translation seam.
 *
 * <p>Round-trip notes (asymmetries are inherent to the native enums, not bugs):
 * <ul>
 *   <li><b>Equipment</b> {@code ACTIVE↔PUBLISHED}, {@code PENDING_REVIEW↔PENDING_REVIEW},
 *       {@code REJECTED↔REJECTED}. Equipment has no PRIVATE/ARCHIVED enum member,
 *       so {@code toEquipment(PRIVATE)}→{@code PENDING_REVIEW} and
 *       {@code toEquipment(ARCHIVED)}→{@code REJECTED} (nearest safe non-public state).
 *   <li><b>Exercise</b> {@code PUBLISHED↔PUBLISHED}, {@code DRAFT↔PENDING_REVIEW}
 *       (a DRAFT exercise is pre-publication = awaiting review), {@code ARCHIVED↔ARCHIVED}.
 *       Exercise has no REJECTED member → {@code toExercise(REJECTED)}→{@code ARCHIVED}.
 *   <li><b>Food</b> {@code VERIFIED↔PUBLISHED} (trusted/app-wide tier),
 *       {@code UNVERIFIED↔PENDING_REVIEW} (usable by creator but not app-wide; the
 *       D7 reconciliation — see spec §2). Food has only those two members, so
 *       PRIVATE/REJECTED/ARCHIVED all map down to {@code UNVERIFIED}.
 *   <li><b>Program</b> (used by the catalog <em>copy</em>'s own state tracking, not
 *       the per-user program): {@code ACTIVE↔PUBLISHED}, {@code DRAFT↔PRIVATE},
 *       {@code ARCHIVED↔ARCHIVED}, {@code COMPLETED↔PUBLISHED}.
 * </ul>
 */
public final class CatalogStatusMapping {

    private CatalogStatusMapping() {}

    // ---- Equipment ----

    public static CatalogStatus fromEquipment(EquipmentStatus s) {
        if (s == null) return CatalogStatus.PRIVATE;
        return switch (s) {
            case ACTIVE -> CatalogStatus.PUBLISHED;
            case PENDING_REVIEW -> CatalogStatus.PENDING_REVIEW;
            case REJECTED -> CatalogStatus.REJECTED;
        };
    }

    public static EquipmentStatus toEquipment(CatalogStatus s) {
        if (s == null) return EquipmentStatus.PENDING_REVIEW;
        return switch (s) {
            case PUBLISHED -> EquipmentStatus.ACTIVE;
            case PENDING_REVIEW, PRIVATE -> EquipmentStatus.PENDING_REVIEW;
            case REJECTED, ARCHIVED -> EquipmentStatus.REJECTED;
        };
    }

    // ---- Exercise ----

    public static CatalogStatus fromExercise(ExerciseStatus s) {
        if (s == null) return CatalogStatus.PRIVATE;
        return switch (s) {
            case PUBLISHED -> CatalogStatus.PUBLISHED;
            case DRAFT -> CatalogStatus.PENDING_REVIEW;
            case ARCHIVED -> CatalogStatus.ARCHIVED;
        };
    }

    public static ExerciseStatus toExercise(CatalogStatus s) {
        if (s == null) return ExerciseStatus.DRAFT;
        return switch (s) {
            case PUBLISHED -> ExerciseStatus.PUBLISHED;
            case PENDING_REVIEW, PRIVATE -> ExerciseStatus.DRAFT;
            case ARCHIVED, REJECTED -> ExerciseStatus.ARCHIVED;
        };
    }

    // ---- Food ----

    public static CatalogStatus fromFood(FoodStatus s) {
        if (s == null) return CatalogStatus.PENDING_REVIEW;
        return switch (s) {
            case VERIFIED -> CatalogStatus.PUBLISHED;
            case UNVERIFIED -> CatalogStatus.PENDING_REVIEW;
        };
    }

    public static FoodStatus toFood(CatalogStatus s) {
        if (s == null) return FoodStatus.UNVERIFIED;
        return s == CatalogStatus.PUBLISHED ? FoodStatus.VERIFIED : FoodStatus.UNVERIFIED;
    }

    // ---- Program (per-user ProgramStatus; informs the catalog copy state) ----

    public static CatalogStatus fromProgram(ProgramStatus s) {
        if (s == null) return CatalogStatus.PRIVATE;
        return switch (s) {
            case ACTIVE, COMPLETED -> CatalogStatus.PUBLISHED;
            case DRAFT -> CatalogStatus.PRIVATE;
            case ARCHIVED -> CatalogStatus.ARCHIVED;
        };
    }

    public static ProgramStatus toProgram(CatalogStatus s) {
        if (s == null) return ProgramStatus.DRAFT;
        return switch (s) {
            case PUBLISHED -> ProgramStatus.ACTIVE;
            case PRIVATE, PENDING_REVIEW -> ProgramStatus.DRAFT;
            case ARCHIVED, REJECTED -> ProgramStatus.ARCHIVED;
        };
    }
}
