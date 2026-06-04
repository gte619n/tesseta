package com.gte619n.healthfitness.api.sync;

import com.gte619n.healthfitness.core.sync.SyncChange;
import com.gte619n.healthfitness.core.sync.SyncPage;
import java.time.Instant;
import java.util.List;

/**
 * Wire shape of the unified delta read (IMPL-AND-20 "API Contracts"). The
 * Android sync engine (Phase 4) consumes this exact JSON.
 *
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "serverTime": "2026-06-02T18:04:11.482Z",
 *   "changes": [
 *     { "collection": "medications", "id": "8f3c…",
 *       "status": "ACTIVE",   "lastUpdate": "…", "doc": { … } },
 *     { "collection": "bloodReadings", "id": "12ab…",
 *       "status": "ARCHIVED", "lastUpdate": "…", "doc": null }
 *   ],
 *   "nextCursor": "eyJ0cyI6MTc…",
 *   "hasMore": true,
 *   "killSwitch": false
 * }
 * </pre>
 */
public record SyncResponse(
    int schemaVersion,
    Instant serverTime,
    List<ChangeDto> changes,
    String nextCursor,
    boolean hasMore,
    boolean killSwitch
) {

    public static SyncResponse from(SyncPage page) {
        List<ChangeDto> dtos = page.changes().stream()
            .map(ChangeDto::from)
            .toList();
        return new SyncResponse(
            page.schemaVersion(),
            page.serverTime(),
            dtos,
            page.nextCursor(),
            page.hasMore(),
            page.killSwitch()
        );
    }

    /**
     * One changed document. {@code status} is the sync lifecycle status
     * ({@code ACTIVE}/{@code ARCHIVED}); {@code ARCHIVED} carries {@code
     * doc:null} as a tombstone. {@code lastUpdate} is the server-clock cursor +
     * LWW ordering key (the {@code updatedAt} field internally).
     */
    public record ChangeDto(
        String collection,
        String id,
        String status,
        Instant lastUpdate,
        Object doc
    ) {
        public static ChangeDto from(SyncChange c) {
            return new ChangeDto(
                c.collection(),
                c.id(),
                c.status().name(),
                c.lastUpdate(),
                c.doc()
            );
        }
    }
}
