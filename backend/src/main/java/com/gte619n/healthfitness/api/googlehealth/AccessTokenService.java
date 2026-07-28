package com.gte619n.healthfitness.api.googlehealth;

import com.gte619n.healthfitness.core.user.GoogleHealthConnection;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthAuthException;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthOAuthClient;
import com.gte619n.healthfitness.integrations.googlehealth.KmsTokenCipher;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

// Loads encrypted refresh tokens, exchanges them for access tokens, and
// caches the result in process. Access tokens live ~1 hour; we cache with
// a 10-minute safety buffer so a single user's webhook stream costs at
// most one KMS-decrypt-and-OAuth-exchange per hour.
@Service
public class AccessTokenService {

    private static final Duration SAFETY_BUFFER = Duration.ofMinutes(10);

    private final UserRepository users;
    private final KmsTokenCipher cipher;
    private final GoogleHealthOAuthClient oauth;
    private final ApplicationEventPublisher events;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public AccessTokenService(
        UserRepository users,
        KmsTokenCipher cipher,
        GoogleHealthOAuthClient oauth,
        ApplicationEventPublisher events
    ) {
        this.users = users;
        this.cipher = cipher;
        this.oauth = oauth;
        this.events = events;
    }

    public String accessTokenFor(String userId) {
        CachedToken cached = cache.get(userId);
        if (cached != null && cached.expiresAt.isAfter(Instant.now().plus(SAFETY_BUFFER))) {
            return cached.accessToken;
        }
        User user = users.findById(userId).orElseThrow(
            () -> new IllegalStateException("Unknown user: " + userId));
        GoogleHealthConnection connection = user.googleHealth();
        if (connection == null) {
            throw new IllegalStateException(
                "User " + userId + " has not connected Google Health");
        }
        var encrypted = new KmsTokenCipher.EncryptedToken(
            connection.refreshTokenCiphertext(),
            connection.dekCiphertext()
        );
        String refreshToken = cipher.decrypt(encrypted);

        GoogleHealthOAuthClient.AccessTokenGrant grant;
        try {
            grant = oauth.exchangeRefreshToken(refreshToken);
        } catch (GoogleHealthAuthException e) {
            // Permanent auth failure — the refresh token is dead. Mark the
            // connection broken (only on the healthy->broken transition, so the
            // reconnect prompt fires once) and rethrow so callers still surface
            // the failure. Transient errors are NOT caught here, so a blip never
            // marks a connection broken.
            cache.remove(userId);
            if (!connection.needsReconnect()) {
                users.markGoogleHealthBroken(userId, e.getMessage());
                events.publishEvent(new GoogleHealthConnectionBrokenEvent(userId, e.getMessage()));
            }
            throw e;
        }
        // Success. If the connection had previously been marked broken, it has
        // recovered (e.g. the user reconnected on another device) — clear the
        // flag so the UI stops nagging.
        if (connection.needsReconnect()) {
            users.recordGoogleHealthConnection(userId, new GoogleHealthConnection(
                connection.healthUserId(),
                connection.refreshTokenCiphertext(),
                connection.dekCiphertext(),
                connection.connectedAt(),
                null,
                null
            ));
        }
        Instant expiry = Instant.now().plusSeconds(grant.expiresInSeconds());
        cache.put(userId, new CachedToken(grant.accessToken(), expiry));
        return grant.accessToken();
    }

    public void invalidate(String userId) {
        cache.remove(userId);
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}
}
