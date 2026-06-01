package com.gte619n.healthfitness.googlehealth;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.GoogleHealthConnection;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataType;
import com.gte619n.healthfitness.integrations.googlehealth.KmsTokenCipher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Connect / disconnect for the Google Health integration. The web client
// completes the incremental OAuth authorization for the
// health_metrics_and_measurements.readonly scope, then POSTs the
// resulting refresh + access token here. We:
//   1. Encrypt the refresh token via KMS envelope encryption.
//   2. Use the access token immediately to call the Google Health API
//      once, parse the healthUserId out of the response.
//   3. Persist {healthUserId, encrypted refresh token} on the user.
//   4. Kick off a virtual-thread backfill of the prior 4 years.
@RestController
@RequestMapping("/api/me/google-health")
public class GoogleHealthConnectController {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final KmsTokenCipher cipher;
    private final GoogleHealthClient googleHealth;
    private final BackfillService backfill;
    private final DailyMetricBackfillService dailyMetricBackfill;
    private final AccessTokenService tokens;

    public GoogleHealthConnectController(
        CurrentUserProvider currentUser,
        UserRepository users,
        KmsTokenCipher cipher,
        GoogleHealthClient googleHealth,
        BackfillService backfill,
        DailyMetricBackfillService dailyMetricBackfill,
        AccessTokenService tokens
    ) {
        this.currentUser = currentUser;
        this.users = users;
        this.cipher = cipher;
        this.googleHealth = googleHealth;
        this.backfill = backfill;
        this.dailyMetricBackfill = dailyMetricBackfill;
        this.tokens = tokens;
    }

    @PostMapping("/connect")
    public ResponseEntity<Void> connect(@RequestBody ConnectRequest body) {
        String userId = currentUser.get().userId();

        String healthUserId = discoverHealthUserId(body.accessToken());

        KmsTokenCipher.EncryptedToken encrypted = cipher.encrypt(body.refreshToken());
        users.recordGoogleHealthConnection(userId, new GoogleHealthConnection(
            healthUserId,
            encrypted.refreshTokenCiphertext(),
            encrypted.dekCiphertext(),
            Instant.now()
        ));
        tokens.invalidate(userId);
        backfill.scheduleBackfill(userId);
        dailyMetricBackfill.scheduleBackfill(userId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping("/connect")
    public ResponseEntity<Void> disconnect() {
        String userId = currentUser.get().userId();
        users.clearGoogleHealthConnection(userId);
        tokens.invalidate(userId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/status")
    public StatusResponse status() {
        String userId = currentUser.get().userId();
        User user = users.findById(userId).orElseThrow(
            () -> new IllegalStateException("Unknown user: " + userId));
        GoogleHealthConnection gh = user.googleHealth();
        if (gh == null) {
            return new StatusResponse(false, null);
        }
        return new StatusResponse(true, gh.connectedAt());
    }

    public record StatusResponse(boolean connected, Instant connectedAt) {}

    private String discoverHealthUserId(String accessToken) {
        // Look back ~1 day at a single data type. We only need one record
        // to read out the healthUserId from its `name` resource path. If
        // a user has truly never recorded a measurement of any kind, we
        // fall back to a synthetic id derived from the access token's
        // intended user; webhooks will arrive with the real id and we
        // reconcile on next save.
        Instant now = Instant.now();
        Instant lookback = now.minus(Duration.ofDays(1));
        for (GoogleHealthDataType type : GoogleHealthDataType.values()) {
            List<GoogleHealthDataPoint> points = googleHealth.listDataPoints(
                accessToken, type, lookback, now);
            if (!points.isEmpty()) {
                return points.get(0).healthUserId();
            }
        }
        // No data in the last day for any type. Try a 4-year lookback so
        // a brand-new connect on an account with ancient data still
        // discovers the healthUserId immediately. If even that's empty,
        // we throw — Google Health requires a healthUserId before
        // webhook notifications make sense.
        Instant ancient = now.minus(Duration.ofDays(1460));
        for (GoogleHealthDataType type : GoogleHealthDataType.values()) {
            List<GoogleHealthDataPoint> points = googleHealth.listDataPoints(
                accessToken, type, ancient, now);
            if (!points.isEmpty()) {
                return points.get(0).healthUserId();
            }
        }
        throw new IllegalStateException(
            "Could not discover healthUserId — no body-comp data points exist for this user");
    }

    public record ConnectRequest(String refreshToken, String accessToken) {}
}
