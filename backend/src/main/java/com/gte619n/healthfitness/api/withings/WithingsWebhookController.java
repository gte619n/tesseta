package com.gte619n.healthfitness.api.withings;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Webhook endpoint for Withings notify callbacks. Withings sends a
// form-urlencoded POST (userid, appli, startdate, enddate) when new data of a
// subscribed category lands; there is no signature, so we authenticate by a
// shared secret carried as a `secret` query param on the registered callback
// URL (constant-time compared).
//
// Two request kinds arrive here:
//   1. The subscribe-time verification probe — Withings POSTs to confirm the
//      callback is reachable and expects HTTP 200. A request with a valid
//      secret but no actionable userid/appli is treated as a probe → 200.
//   2. Real notifications — routed by appli (44 = sleep, 1 = weight) to the
//      sync service, which re-fetches the interval and writes it. Withings
//      retries non-2xx responses, so transient handling errors return 5xx.
//
// The endpoint is public-by-design (no JWT auth filter); the secret query
// param is the gate.
@RestController
@RequestMapping("/api/webhooks/withings")
public class WithingsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WithingsWebhookController.class);

    // Notify categories (appli) we act on.
    private static final int APPLI_SLEEP = 44;
    private static final int APPLI_WEIGHT = 1;

    // Fallback window when a notification omits start/end dates.
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(2);

    private final String configuredSecret;
    private final UserRepository users;
    private final WithingsSyncService sync;

    public WithingsWebhookController(
        @Value("${app.withings.webhook-secret:}") String configuredSecret,
        UserRepository users,
        WithingsSyncService sync
    ) {
        this.configuredSecret = configuredSecret;
        this.users = users;
        this.sync = sync;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
        @RequestParam(name = "secret", required = false) String secret,
        @RequestParam(name = "userid", required = false) String withingsUserId,
        @RequestParam(name = "appli", required = false) Integer appli,
        @RequestParam(name = "startdate", required = false) Long startdate,
        @RequestParam(name = "enddate", required = false) Long enddate
    ) {
        if (!isAuthorized(secret)) {
            log.warn("Withings webhook rejected — secret mismatch");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (withingsUserId == null || withingsUserId.isBlank() || appli == null) {
            // Subscribe-time verification probe (or an empty ping): ack so
            // Withings accepts the subscription.
            log.info("Withings webhook probe accepted");
            return ResponseEntity.ok().build();
        }

        Optional<User> match = users.findByWithingsUserId(withingsUserId);
        if (match.isEmpty()) {
            log.warn("Withings webhook for unknown withingsUserId={}", withingsUserId);
            return ResponseEntity.ok().build();
        }
        String userId = match.get().userId();

        Instant now = Instant.now();
        Instant from = startdate != null ? Instant.ofEpochSecond(startdate) : now.minus(DEFAULT_WINDOW);
        Instant to = enddate != null ? Instant.ofEpochSecond(enddate) : now;

        try {
            switch (appli) {
                case APPLI_SLEEP -> sync.importSleep(userId, from, to);
                case APPLI_WEIGHT -> sync.importBody(userId, from, to);
                default -> log.info("Withings webhook unhandled appli={} user={}", appli, userId);
            }
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.error("Withings webhook handling failed user={} appli={}: {}",
                userId, appli, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean isAuthorized(String provided) {
        if (configuredSecret.isBlank() || provided == null) return false;
        return constantTimeEquals(
            provided.getBytes(StandardCharsets.UTF_8),
            configuredSecret.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
