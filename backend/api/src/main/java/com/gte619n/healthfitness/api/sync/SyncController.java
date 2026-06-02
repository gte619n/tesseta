package com.gte619n.healthfitness.api.sync;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.sync.SyncPage;
import com.gte619n.healthfitness.core.sync.SyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified delta-read endpoint (IMPL-AND-20, D6). One call returns every changed
 * in-scope document for the current user since an opaque cursor — including
 * {@link com.gte619n.healthfitness.core.sync.SyncStatus#ARCHIVED} tombstones so
 * an offline client can drop soft-deleted rows — paginated by {@code nextCursor}
 * / {@code hasMore}.
 *
 * <pre>GET /api/me/sync?since=&lt;cursor&gt;&amp;limit=500&amp;schemaVersion=1</pre>
 *
 * <p>{@code since} omitted ⇒ initial full sync. The Android client windows heavy
 * time-series to the last 14 days itself (D14); the server enumerates fully and
 * pages. When the requested {@code schemaVersion} differs from the server's, the
 * server still answers with its own {@code schemaVersion} so the client can wipe
 * Room and full-resync (D13) — it never errors on a version mismatch.
 */
@RestController
@RequestMapping("/api/me/sync")
public class SyncController {

    private final CurrentUserProvider currentUser;
    private final SyncService syncService;

    public SyncController(CurrentUserProvider currentUser, SyncService syncService) {
        this.currentUser = currentUser;
        this.syncService = syncService;
    }

    @GetMapping
    public SyncResponse sync(
        @RequestParam(value = "since", required = false) String since,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "schemaVersion", required = false) Integer schemaVersion
    ) {
        String userId = currentUser.get().userId();
        // A malformed cursor throws IllegalArgumentException ⇒ 400 via the
        // global handler, rather than silently resyncing everything.
        SyncPage page = syncService.page(userId, since, limit);
        return SyncResponse.from(page);
    }
}
