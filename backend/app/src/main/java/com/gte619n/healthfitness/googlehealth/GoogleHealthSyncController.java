package com.gte619n.healthfitness.googlehealth;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Admin-only manual Google Health daily-metric sync. Runs the same
// DailyMetricBackfillService the connect flow schedules, for an explicit
// target user, and returns the per-type stored counts. Useful to re-pull a
// user's history on demand (e.g. after a reconnect that broadened scopes).
//
// Gated by @AdminOnly. The target user is passed explicitly so the call also
// works under dev-mode auth, where the current principal's userId is the
// X-Dev-User header value rather than a real Firestore id.
@RestController
@RequestMapping("/api/admin/google-health")
@AdminOnly
public class GoogleHealthSyncController {

    private final UserRepository users;
    private final DailyMetricBackfillService backfill;

    public GoogleHealthSyncController(UserRepository users, DailyMetricBackfillService backfill) {
        this.users = users;
        this.backfill = backfill;
    }

    @PostMapping("/sync")
    public ResponseEntity<SyncReport> sync(@RequestParam String userId) {
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest()
                .body(new SyncReport(userId, null, null, Map.of(), "unknown userId"));
        }
        if (user.googleHealth() == null) {
            return ResponseEntity.badRequest().body(new SyncReport(
                userId, user.email(), null, Map.of(), "user has not connected Google Health"));
        }

        Map<String, Integer> stored = backfill.runBackfill(userId);
        String note = stored.isEmpty()
            ? "backfill could not start (see server logs — likely token exchange)"
            : "backfill complete";
        return ResponseEntity.ok(new SyncReport(
            userId, user.email(), user.googleHealth().healthUserId(), stored, note));
    }

    public record SyncReport(
        String userId,
        String email,
        String healthUserId,
        Map<String, Integer> storedByType,
        String note
    ) {}
}
