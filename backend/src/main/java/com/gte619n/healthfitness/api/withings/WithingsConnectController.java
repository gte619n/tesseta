package com.gte619n.healthfitness.api.withings;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.core.user.WithingsConnection;
import com.gte619n.healthfitness.integrations.googlehealth.KmsTokenCipher;
import com.gte619n.healthfitness.integrations.withings.WithingsOAuthClient;
import com.gte619n.healthfitness.integrations.withings.WithingsSubscriberClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Connect / disconnect for the Withings integration. Both web and Android
// perform a browser OAuth2 authorization-code redirect and forward the
// resulting {code, redirectUri} here. The client secret stays server-side:
//   1. Redeem the code for {userid, access, refresh} (refresh token rotates).
//   2. Encrypt the refresh token via KMS envelope encryption (shared cipher).
//   3. Persist {withingsUserId, encrypted refresh token} on the user.
//   4. Seed the access-token cache (avoids an immediate extra rotation), then
//      kick off a virtual-thread backfill + webhook subscription.
//
// redirectUri is echoed from the client because Withings requires the token
// exchange's redirect_uri to exactly match the one used to start the flow, and
// web vs Android use different registered URIs.
@RestController
@RequestMapping("/api/me/withings")
public class WithingsConnectController {

    private static final Logger log = LoggerFactory.getLogger(WithingsConnectController.class);

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final KmsTokenCipher cipher;
    private final WithingsOAuthClient oauthClient;
    private final WithingsAccessTokenService tokens;
    private final WithingsBackfillService backfill;
    private final WithingsSubscriberClient subscriber;
    private final String callbackBaseUrl;
    private final String webhookSecret;

    public WithingsConnectController(
        CurrentUserProvider currentUser,
        UserRepository users,
        KmsTokenCipher cipher,
        WithingsOAuthClient oauthClient,
        WithingsAccessTokenService tokens,
        WithingsBackfillService backfill,
        WithingsSubscriberClient subscriber,
        @Value("${app.withings.callback-url:}") String callbackBaseUrl,
        @Value("${app.withings.webhook-secret:}") String webhookSecret
    ) {
        this.currentUser = currentUser;
        this.users = users;
        this.cipher = cipher;
        this.oauthClient = oauthClient;
        this.tokens = tokens;
        this.backfill = backfill;
        this.subscriber = subscriber;
        this.callbackBaseUrl = callbackBaseUrl;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping("/connect")
    public ResponseEntity<Void> connect(@RequestBody ConnectRequest body) {
        String userId = currentUser.get().userId();
        WithingsOAuthClient.TokenGrant grant =
            oauthClient.exchangeAuthCode(body.code(), body.redirectUri());

        KmsTokenCipher.EncryptedToken encrypted = cipher.encrypt(grant.refreshToken());
        users.recordWithingsConnection(userId, new WithingsConnection(
            grant.withingsUserId(),
            encrypted.refreshTokenCiphertext(),
            encrypted.dekCiphertext(),
            null,   // first connect: persistence stamps the server connect time
            null,
            null
        ));
        // Seed the just-exchanged access token so backfill doesn't immediately
        // refresh (and rotate) again.
        tokens.seed(userId, grant.accessToken(), grant.expiresInSeconds());
        backfill.scheduleBackfill(userId);
        // Subscribe webhooks off the request path (a Withings callback probe can
        // be slow, and a subscribe failure must not fail the connect).
        String accessToken = grant.accessToken();
        String callbackUrl = effectiveCallbackUrl();
        Executors.newVirtualThreadPerTaskExecutor().submit(
            () -> subscriber.subscribeAll(accessToken, callbackUrl));
        log.info("Withings connected user={} withingsUserId={}", userId, grant.withingsUserId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    // The callback base URL is kept secret-free in config so it can live in
    // plain deploy env; the webhook secret (the only auth on the signature-less
    // notify callback) is appended here as the `?secret=` param the webhook
    // controller checks. Keeping them separate avoids committing the secret.
    private String effectiveCallbackUrl() {
        if (callbackBaseUrl.isBlank() || webhookSecret.isBlank()) {
            return callbackBaseUrl;
        }
        String separator = callbackBaseUrl.contains("?") ? "&" : "?";
        return callbackBaseUrl + separator + "secret="
            + URLEncoder.encode(webhookSecret, StandardCharsets.UTF_8);
    }

    @DeleteMapping("/connect")
    public ResponseEntity<Void> disconnect() {
        String userId = currentUser.get().userId();
        users.clearWithingsConnection(userId);
        tokens.invalidate(userId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return statusFor(currentUser.get().userId());
    }

    // Actively probe the connection: force a token exchange. A dead refresh
    // token throws WithingsAuthException, which WithingsAccessTokenService
    // records as broken before it reaches us — so we swallow it and report the
    // freshly-updated status.
    @PostMapping("/check")
    public StatusResponse check() {
        String userId = currentUser.get().userId();
        User user = users.findById(userId).orElseThrow(
            () -> new IllegalStateException("Unknown user: " + userId));
        if (user.withings() != null) {
            try {
                tokens.accessTokenFor(userId);
            } catch (RuntimeException ignored) {
                // Broken state (if any) is already persisted; re-read reflects truth.
            }
        }
        return statusFor(userId);
    }

    private StatusResponse statusFor(String userId) {
        User user = users.findById(userId).orElseThrow(
            () -> new IllegalStateException("Unknown user: " + userId));
        WithingsConnection w = user.withings();
        if (w == null) {
            return new StatusResponse(false, null, false, null, null);
        }
        return new StatusResponse(
            true, w.connectedAt(), w.needsReconnect(), w.brokenAt(), w.brokenReason());
    }

    public record StatusResponse(
        boolean connected,
        Instant connectedAt,
        boolean needsReconnect,
        Instant brokenAt,
        String brokenReason
    ) {}

    public record ConnectRequest(String code, String redirectUri) {}
}
