package com.gte619n.healthfitness.core.catalog;

/**
 * IMPL-MULTIUSER-01 P3.1 (D8) — the one shared lifecycle vocabulary spoken by
 * the curation console across every heterogeneous catalog entity (programs,
 * ad-hoc workouts, equipment, exercises, foods).
 *
 * <p>This vocabulary is <em>mapped</em> onto each entity's own stored enum
 * ({@code EquipmentStatus}/{@code ExerciseStatus}/{@code FoodStatus}/
 * {@code ProgramStatus}); the physical enums are deliberately NOT migrated
 * (D8 "map, don't migrate"). See {@link CatalogStatusMapping}.
 *
 * <ul>
 *   <li>{@code PRIVATE} — a per-user object not (yet) submitted for promotion.
 *   <li>{@code PENDING_REVIEW} — submitted, awaiting the mandatory admin sign-off (D7).
 *   <li>{@code PUBLISHED} — approved, surfaced in the shared/app-wide catalog.
 *   <li>{@code REJECTED} — admin declined; carries a {@code rejectedReason} (provenance).
 *   <li>{@code ARCHIVED} — retired from listings (soft-deleted / tombstoned).
 * </ul>
 */
public enum CatalogStatus {
    PRIVATE,
    PENDING_REVIEW,
    PUBLISHED,
    REJECTED,
    ARCHIVED
}
