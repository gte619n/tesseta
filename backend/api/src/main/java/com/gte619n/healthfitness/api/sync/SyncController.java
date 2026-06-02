package com.gte619n.healthfitness.api.sync;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.sync.SyncPage;
import com.gte619n.healthfitness.core.sync.SyncService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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
 * <pre>GET /api/me/sync?since=&lt;cursor&gt;&amp;limit=500&amp;schemaVersion=1&amp;recentSince=2026-05-19</pre>
 *
 * <p>{@code since} omitted ⇒ initial full sync. {@code recentSince=&lt;ISO date&gt;}
 * (optional) bounds the <b>heavy time-series</b> collections (dailyMetrics,
 * bodyComposition, bloodReadings, nutrition entries/daily logs,
 * weeklyWorkoutAggregates) to documents on or after that date, while CRUD
 * domains always return in full (IMPL-AND-20 #37 / D14). The Android client
 * passes {@code recentSince = today − 14d} on its <em>first</em> sync to release
 * the UI fast, then omits it for the later unbounded backfill — the cursor
 * continues correctly into the older heavy history because the bound is a pure
 * emission filter that never alters the {@code (updatedAt, collection, id)}
 * cursor key, and re-applying already-synced rows is an idempotent LWW no-op.
 *
 * <p>When the requested {@code schemaVersion} differs from the server's, the
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
        @RequestParam(value = "schemaVersion", required = false) Integer schemaVersion,
        @RequestParam(value = "recentSince", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate recentSince
    ) {
        String userId = currentUser.get().userId();
        // A malformed cursor throws IllegalArgumentException ⇒ 400 via the
        // global handler, rather than silently resyncing everything. recentSince
        // (when present) bounds heavy time-series to docs on/after that date
        // (IMPL-AND-20 #37 / D14); CRUD domains are always returned in full.
        SyncPage page = syncService.page(userId, since, limit, recentSince);
        return SyncResponse.from(page);
    }
}
