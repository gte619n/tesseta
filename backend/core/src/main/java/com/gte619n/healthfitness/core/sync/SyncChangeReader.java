package com.gte619n.healthfitness.core.sync;

import java.util.List;

/**
 * Cross-collection change source for the unified delta-read API
 * (IMPL-AND-20, Phase 1). Implementations enumerate every in-scope per-user
 * collection (and subcollection) for documents whose {@code updatedAt} server
 * timestamp is strictly after the supplied cursor — or all of them on an
 * initial full sync — and return them in {@link SyncChange#CANONICAL_ORDER}.
 *
 * <p><b>Tombstones are included.</b> Unlike the normal list/find repository
 * paths, the reader must surface {@link SyncStatus#ARCHIVED} documents too: the
 * delta API exists precisely to tell offline clients to drop soft-deleted rows.
 *
 * <h2>Collection / id naming contract (consumed by Android Phase 4)</h2>
 *
 * The {@code collection} string on each {@link SyncChange} is the routing key
 * the client maps to a Room mirror table. Top-level per-user collections use
 * their Firestore subcollection name verbatim. Subcollections are encoded so
 * the change is self-describing without a separate parent lookup:
 *
 * <ul>
 *   <li>top-level: {@code collection="medications"}, {@code id="<medId>"}</li>
 *   <li>nested:    {@code collection="medications/adherence"},
 *       {@code id="<medId>/<adherenceId>"} — the parent id is the first path
 *       segment of {@code id}, mirroring the Firestore document path.</li>
 * </ul>
 *
 * <p>The concrete list of emitted {@code collection} values is fixed and
 * documented on the Firestore implementation and in the plan doc.
 */
public interface SyncChangeReader {

    /**
     * Read up to {@code limit} changes for {@code userId} strictly after
     * {@code since} (a {@code null} cursor means "from the beginning"),
     * returned in {@link SyncChange#CANONICAL_ORDER}.
     *
     * <p>Implementations may over-fetch internally but MUST return a globally
     * ordered, de-duplicated prefix so the caller can take a page and derive a
     * correct {@code nextCursor}. Returning more than {@code limit} is allowed
     * (the caller truncates and computes {@code hasMore}); returning fewer than
     * the total available is only allowed when at least {@code limit} were
     * returned.
     *
     * @param userId the owning user (Google {@code sub})
     * @param since  resume strictly after this cursor, or {@code null} for a
     *               full initial enumeration
     * @param limit  soft page size; the caller truncates to exactly this
     */
    List<SyncChange> readChanges(String userId, SyncCursor since, int limit);
}
