package com.gte619n.healthfitness.googlehealth;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Admin-only manual Google Health sync + inspect endpoint.
//
// Two jobs in one call:
//   1. INSPECT — for each daily-metric data type, fetch the first page of
//      the user's data points RAW (no mapping) so the actual on-the-wire
//      JSON field shapes can be examined. This is how we verify the
//      DailyMetricMapper field names against reality instead of docs.
//   2. SYNC — when persist=true, run the real DailyMetricBackfillService so
//      the mapped values land in Firestore.
//
// Gated by @AdminOnly. The target user is passed explicitly (the admin's
// own identity authorizes the call; the userId selects whose data to pull),
// which also works under dev-mode auth where the current principal's userId
// is the X-Dev-User header value rather than a real Firestore id.
@RestController
@RequestMapping("/api/admin/google-health")
@AdminOnly
public class GoogleHealthSyncController {

    private static final Logger log = LoggerFactory.getLogger(GoogleHealthSyncController.class);
    private static final int MAX_BODY_CHARS = 12000;

    private final UserRepository users;
    private final AccessTokenService tokens;
    private final GoogleHealthClient googleHealth;
    private final DailyMetricBackfillService backfill;

    public GoogleHealthSyncController(
        UserRepository users,
        AccessTokenService tokens,
        GoogleHealthClient googleHealth,
        DailyMetricBackfillService backfill
    ) {
        this.users = users;
        this.tokens = tokens;
        this.googleHealth = googleHealth;
        this.backfill = backfill;
    }

    @PostMapping("/sync")
    public ResponseEntity<SyncReport> sync(
        @RequestParam String userId,
        @RequestParam(defaultValue = "7") int days,
        @RequestParam(defaultValue = "false") boolean persist,
        @RequestParam(defaultValue = "false") boolean noFilter
    ) {
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(
                new SyncReport(userId, null, null, null, List.of(), "unknown userId"));
        }
        if (user.googleHealth() == null) {
            return ResponseEntity.badRequest().body(
                new SyncReport(userId, user.email(), null, null, List.of(),
                    "user has not connected Google Health"));
        }

        String healthUserId = user.googleHealth().healthUserId();
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(days));

        String accessToken;
        try {
            accessToken = tokens.accessTokenFor(userId);
        } catch (RuntimeException e) {
            log.error("Admin sync: could not obtain access token user={}", userId, e);
            return ResponseEntity.status(502).body(
                new SyncReport(userId, user.email(), healthUserId,
                    new Window(from.toString(), to.toString()), List.of(),
                    "access-token exchange failed: " + e.getMessage()));
        }

        List<TypeResult> results = new ArrayList<>();
        for (DailyMetricDataType type : DailyMetricDataType.values()) {
            GoogleHealthClient.RawResponse raw = noFilter
                ? googleHealth.rawFirstPageNoFilter(accessToken, type.urlSegment(), 25)
                : googleHealth.rawFirstPage(
                    accessToken, type.urlSegment(), type.filterFieldName(), from, to);
            String body = raw.body() == null ? "" : raw.body();
            boolean truncated = body.length() > MAX_BODY_CHARS;
            results.add(new TypeResult(
                type.name(),
                type.urlSegment(),
                raw.url(),
                raw.statusCode(),
                truncated ? body.substring(0, MAX_BODY_CHARS) : body,
                truncated));
            log.info("Admin sync inspect user={} type={} status={} bodyLen={}",
                userId, type.urlSegment(), raw.statusCode(), body.length());
        }

        String note = "inspect-only";
        if (persist) {
            backfill.runBackfill(userId);
            note = "backfill triggered (see server logs for stored counts)";
        }

        return ResponseEntity.ok(new SyncReport(
            userId, user.email(), healthUserId,
            new Window(from.toString(), to.toString()), results, note));
    }

    public record SyncReport(
        String userId,
        String email,
        String healthUserId,
        Window window,
        List<TypeResult> results,
        String note
    ) {}

    public record Window(String from, String to) {}

    public record TypeResult(
        String dataType,
        String urlSegment,
        String requestUrl,
        int statusCode,
        String body,
        boolean truncated
    ) {}
}
