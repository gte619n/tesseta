package com.gte619n.healthfitness.core.sync;

import java.time.Instant;
import java.util.List;

/**
 * One page of the unified delta read (IMPL-AND-20, D6), assembled by
 * {@link SyncService}. The API layer renders this into the wire response,
 * mapping each {@link SyncChange}'s {@code lastUpdate} to the contract field
 * {@code lastUpdate} and emitting {@code doc:null} for tombstones.
 *
 * @param schemaVersion the server's sync protocol version (D13)
 * @param serverTime    the server clock at assembly time
 * @param changes       this page's changes, in {@link SyncChange#CANONICAL_ORDER}
 * @param nextCursor    opaque cursor to fetch the next page (stable when no
 *                      changes remain); null only on an empty initial sync
 * @param hasMore       true when more changes remain beyond this page
 * @param killSwitch    D13 remote kill-switch; true ⇒ client drops to
 *                      live-network mode
 */
public record SyncPage(
    int schemaVersion,
    Instant serverTime,
    List<SyncChange> changes,
    String nextCursor,
    boolean hasMore,
    boolean killSwitch
) {}
