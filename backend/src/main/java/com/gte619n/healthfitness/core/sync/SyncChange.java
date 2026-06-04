package com.gte619n.healthfitness.core.sync;

import java.time.Instant;
import java.util.Comparator;

/**
 * One changed document in the unified delta-read stream (IMPL-AND-20, D6).
 *
 * @param collection  the logical collection name the Android client maps to a
 *                     Room mirror table. For top-level per-user collections this
 *                     is the Firestore subcollection name (e.g. {@code
 *                     "medications"}, {@code "bloodReadings"}). For subcollections
 *                     it encodes the nesting so the client can route it (see the
 *                     {@code collection}/{@code id} convention documented on
 *                     {@link SyncChangeReader}).
 * @param id          the document identity within {@code collection}. For
 *                     subcollections this includes parent ids so the change is
 *                     globally addressable (e.g. {@code "<medId>/<adherenceId>"}).
 * @param status      sync lifecycle status; {@link SyncStatus#ARCHIVED} is a
 *                     tombstone and carries a {@code null} {@code doc}.
 * @param lastUpdate  the server timestamp that is both the cursor key and the
 *                     last-write-wins ordering authority (D3).
 * @param doc         the serialized domain payload (a response DTO Jackson will
 *                     render) for an {@code ACTIVE} change; {@code null} for an
 *                     {@code ARCHIVED} tombstone.
 */
public record SyncChange(
    String collection,
    String id,
    SyncStatus status,
    Instant lastUpdate,
    Object doc
) {

    /**
     * Canonical total order across the whole sync set, matching the cursor's
     * tiebreak: {@code (lastUpdate ASC, collection ASC, id ASC)}. Stable and
     * deterministic so paging never skips or duplicates a change.
     */
    public static final Comparator<SyncChange> CANONICAL_ORDER =
        Comparator.comparingLong(SyncChange::lastUpdateMillis)
            .thenComparing(SyncChange::collection)
            .thenComparing(SyncChange::id);

    /** Epoch-millis form of {@link #lastUpdate} (0 when absent). */
    public long lastUpdateMillis() {
        return lastUpdate == null ? 0L : lastUpdate.toEpochMilli();
    }

    /** Cursor that points exactly at this change (used as a page's nextCursor). */
    public SyncCursor toCursor() {
        return new SyncCursor(lastUpdateMillis(), collection, id);
    }
}
