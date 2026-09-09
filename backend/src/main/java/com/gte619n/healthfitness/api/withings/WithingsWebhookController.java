package com.gte619n.healthfitness.api.withings;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
//      sync service, which re-fetches the interval and writes it.
//
// Withings times out the callback after 2 seconds, but a real notification's
// handling (token refresh + a Withings API re-fetch + Firestore writes) can
// take longer than that. So we ack 200 immediately after authenticating and do
// the sync off the request path on a virtual thread. That means we never return
// a retryable 5xx — a dropped sync is backstopped by the periodic refresh sweep
// (see WithingsBackfillService), which is the right trade to avoid the timeout.
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
    private final Executor worker;

    @Autowired
    public WithingsWebhookController(
        @Value("${app.withings.webhook-secret:}") String configuredSecret,
        UserRepository users,
        WithingsSyncService sync
    ) {
        this(configuredSecret, users, sync, Executors.newVirtualThreadPerTaskExecutor());
    }

    // Visible for testing: inject a direct (same-thread) executor so the async
    // dispatch can be asserted deterministically.
    WithingsWebhookController(
        String configuredSecret,
        UserRepository users,
        WithingsSyncService sync,
        Executor worker
    ) {
        this.configuredSecret = configuredSecret;
        this.users = users;
        this.sync = sync;
        this.worker = worker;
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

        // Ack now (Withings' 2s timeout) and re-fetch the window off the request
        // path — the sync can outlast the timeout, and the refresh sweep covers
        // anything dropped here.
        worker.execute(() -> process(withingsUserId, appli, startdate, enddate));
        return ResponseEntity.ok().build();
    }

    // Resolve the user, re-fetch the notified window, and persist. Runs off the
    // request thread, so failures are logged rather than surfaced as a 5xx.
    private void process(String withingsUserId, int appli, Long startdate, Long enddate) {
        try {
            Optional<User> match = users.findByWithingsUserId(withingsUserId);
            if (match.isEmpty()) {
                log.warn("Withings webhook for unknown withingsUserId={}", withingsUserId);
                return;
            }
            String userId = match.get().userId();

            Instant now = Instant.now();
            Instant from = startdate != null ? Instant.ofEpochSecond(startdate) : now.minus(DEFAULT_WINDOW);
            Instant to = enddate != null ? Instant.ofEpochSecond(enddate) : now;

            switch (appli) {
                case APPLI_SLEEP -> sync.importSleep(userId, from, to);
                case APPLI_WEIGHT -> sync.importBody(userId, from, to);
                default -> log.info("Withings webhook unhandled appli={} user={}", appli, userId);
            }
        } catch (RuntimeException e) {
            log.error("Withings webhook async handling failed withingsUserId={} appli={}: {}",
                withingsUserId, appli, e.getMessage(), e);
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
