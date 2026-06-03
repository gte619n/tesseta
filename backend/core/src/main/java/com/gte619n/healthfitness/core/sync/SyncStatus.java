package com.gte619n.healthfitness.core.sync;

/**
 * Universal sync lifecycle status for every in-scope per-user collection
 * (IMPL-AND-20, decisions D2/D13).
 *
 * <p>{@link #ACTIVE} is a live row; {@link #ARCHIVED} is a soft-delete
 * tombstone — the document remains in Firestore so the delta-sync API can
 * tell offline clients to drop it, but normal list/find paths exclude it.
 *
 * <p>Persisted under the dedicated Firestore key {@code syncStatus} (NOT the
 * domain {@code status} key already used by MedicationStatus/GoalStatus). A
 * missing/absent field reads back as {@link #ACTIVE} (D13 lazy default), so
 * legacy documents written before this scheme need no migration.
 */
public enum SyncStatus {
    ACTIVE,
    ARCHIVED
}
