package com.gte619n.healthfitness.core.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the unified delta-read API (IMPL-AND-20, D6): pulls a page of
 * changes from the {@link SyncChangeReader}, truncates to the requested limit,
 * derives {@code nextCursor}/{@code hasMore}, and assembles the response
 * envelope including {@code schemaVersion}, {@code serverTime} and the remote
 * {@code killSwitch} (D13).
 *
 * <p>The cursor tiebreaker guarantees no-skip/no-dup paging even when many
 * documents share the same {@code lastUpdate}: the reader returns changes in
 * {@link SyncChange#CANONICAL_ORDER} and {@link #page} resumes strictly after
 * the previous page's last emitted change.
 */
@Service
public class SyncService {

    /**
     * Wire schema version of the sync protocol. A client that requests a
     * different version is told the server's version so it can wipe Room and
     * full-resync (D13). Bump this on any breaking change to the change JSON.
     */
    public static final int SYNC_SCHEMA_VERSION = 1;

    /** Default page size when the client does not specify {@code limit}. */
    public static final int DEFAULT_LIMIT = 500;

    /** Hard upper bound so a client cannot request an unbounded page. */
    public static final int MAX_LIMIT = 1000;

    private final SyncChangeReader reader;
    private final boolean killSwitch;

    public SyncService(
        SyncChangeReader reader,
        @Value("${app.sync.kill-switch:false}") boolean killSwitch
    ) {
        this.reader = reader;
        this.killSwitch = killSwitch;
    }

    /**
     * Build one delta page for {@code userId}.
     *
     * @param encodedSince   opaque cursor from the client, or null/blank for an
     *                       initial full sync
     * @param requestedLimit client page size; clamped to {@code [1, MAX_LIMIT]}
     */
    public SyncPage page(String userId, String encodedSince, Integer requestedLimit) {
        SyncCursor since = SyncCursor.decode(encodedSince); // throws on malformed
        int limit = clampLimit(requestedLimit);

        // Ask for one extra so we can tell whether more remain beyond this page
        // without a second round-trip.
        List<SyncChange> fetched = reader.readChanges(userId, since, limit + 1);

        // The reader returns canonical order, but normalize defensively so the
        // cursor/hasMore math is correct regardless of impl quirks.
        List<SyncChange> ordered = new ArrayList<>(fetched);
        ordered.sort(SyncChange.CANONICAL_ORDER);

        boolean hasMore = ordered.size() > limit;
        List<SyncChange> pageChanges =
            hasMore ? new ArrayList<>(ordered.subList(0, limit)) : ordered;

        String nextCursor = null;
        if (!pageChanges.isEmpty()) {
            // Always advance the cursor to the last emitted change so the next
            // request resumes strictly after it (no-dup), and an empty next
            // page just confirms hasMore=false.
            nextCursor = pageChanges.get(pageChanges.size() - 1).toCursor().encode();
        } else if (encodedSince != null && !encodedSince.isBlank()) {
            // No new changes since the client's cursor: keep it stable.
            nextCursor = encodedSince;
        }

        return new SyncPage(
            SYNC_SCHEMA_VERSION,
            Instant.now(),
            pageChanges,
            nextCursor,
            hasMore,
            killSwitch
        );
    }

    private static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
