package com.gte619n.healthfitness.api.withings;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Admin-only manual Withings operations, mirroring GoogleHealthSyncController:
//   - /sync?userId=...   re-pull one user's full history (on-demand backfill).
//   - /refresh-all       run the safety-net sweep across all connected users.
// Gated by @AdminOnly; the target user is passed explicitly so it also works
// under dev-mode auth.
@RestController
@RequestMapping("/api/admin/withings")
@AdminOnly
public class WithingsSyncController {

    private final UserRepository users;
    private final WithingsBackfillService backfill;
    private final WithingsRefreshService refresh;

    public WithingsSyncController(
        UserRepository users,
        WithingsBackfillService backfill,
        WithingsRefreshService refresh
    ) {
        this.users = users;
        this.backfill = backfill;
        this.refresh = refresh;
    }

    @PostMapping("/sync")
    public ResponseEntity<SyncReport> sync(@RequestParam String userId) {
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(new SyncReport(userId, null, 0, "unknown userId"));
        }
        if (user.withings() == null) {
            return ResponseEntity.badRequest().body(new SyncReport(
                userId, user.email(), 0, "user has not connected Withings"));
        }
        int stored = backfill.runBackfill(userId);
        return ResponseEntity.ok(new SyncReport(userId, user.email(), stored, "backfill complete"));
    }

    @PostMapping("/refresh-all")
    public ResponseEntity<WithingsRefreshService.Summary> refreshAll() {
        return ResponseEntity.ok(refresh.refreshAll());
    }

    public record SyncReport(String userId, String email, int stored, String note) {}
}
