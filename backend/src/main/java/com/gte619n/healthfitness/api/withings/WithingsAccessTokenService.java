package com.gte619n.healthfitness.api.withings;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.core.user.WithingsConnection;
import com.gte619n.healthfitness.integrations.googlehealth.KmsTokenCipher;
import com.gte619n.healthfitness.integrations.withings.WithingsAuthException;
import com.gte619n.healthfitness.integrations.withings.WithingsOAuthClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

// Loads encrypted refresh tokens, exchanges them for access tokens, and caches
// the result in process. Access tokens live ~3 hours; we cache with a 10-minute
// safety buffer. Reuses the Google Health KmsTokenCipher bean for envelope
// encryption (same KMS key encrypts both providers' refresh tokens).
//
// Withings-specific twist vs AccessTokenService: the refresh token ROTATES on
// every exchange. After a successful refresh we re-encrypt and re-persist the
// new refresh token via recordWithingsConnection. Because a stale refresh token
// is invalidated the instant its successor is minted, refreshes are serialized
// per user (a lock) so two concurrent callers can't burn each other's token.
@Service
public class WithingsAccessTokenService {

    private static final Duration SAFETY_BUFFER = Duration.ofMinutes(10);

    private final UserRepository users;
    private final KmsTokenCipher cipher;
    private final WithingsOAuthClient oauth;
    private final ApplicationEventPublisher events;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public WithingsAccessTokenService(
        UserRepository users,
        KmsTokenCipher cipher,
        WithingsOAuthClient oauth,
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
        // Serialize refreshes per user: rotation invalidates the prior refresh
        // token, so two threads exchanging concurrently would clobber each other.
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            // Re-check: another thread may have refreshed while we waited.
            CachedToken now = cache.get(userId);
            if (now != null && now.expiresAt.isAfter(Instant.now().plus(SAFETY_BUFFER))) {
                return now.accessToken;
            }
            return refresh(userId);
        }
    }

    private String refresh(String userId) {
        User user = users.findById(userId).orElseThrow(
            () -> new IllegalStateException("Unknown user: " + userId));
        WithingsConnection connection = user.withings();
        if (connection == null) {
            throw new IllegalStateException("User " + userId + " has not connected Withings");
        }
        String refreshToken = cipher.decrypt(new KmsTokenCipher.EncryptedToken(
            connection.refreshTokenCiphertext(), connection.dekCiphertext()));

        WithingsOAuthClient.TokenGrant grant;
        try {
            grant = oauth.exchangeRefreshToken(refreshToken);
        } catch (WithingsAuthException e) {
            // Permanent auth failure — the refresh token is dead. Mark broken on
            // the healthy->broken transition so the reconnect prompt fires once.
            cache.remove(userId);
            if (!connection.needsReconnect()) {
                users.markWithingsBroken(userId, e.getMessage());
                events.publishEvent(new WithingsConnectionBrokenEvent(userId, e.getMessage()));
            }
            throw e;
        }

        // Rotation: persist the freshly-minted refresh token. Passing the
        // existing connectedAt (non-null) preserves the original connect time
        // and — via recordWithingsConnection — clears any prior broken flag.
        KmsTokenCipher.EncryptedToken encrypted = cipher.encrypt(grant.refreshToken());
        users.recordWithingsConnection(userId, new WithingsConnection(
            connection.withingsUserId(),
            encrypted.refreshTokenCiphertext(),
            encrypted.dekCiphertext(),
            connection.connectedAt(),
            null,
            null
        ));

        Instant expiry = Instant.now().plusSeconds(grant.expiresInSeconds());
        cache.put(userId, new CachedToken(grant.accessToken(), expiry));
        return grant.accessToken();
    }

    /**
     * Seed the cache with an access token obtained out-of-band (the connect
     * flow already exchanged one). Avoids an immediate extra refresh — and the
     * extra rotation it would cause — right after connect.
     */
    public void seed(String userId, String accessToken, long expiresInSeconds) {
        cache.put(userId, new CachedToken(accessToken,
            Instant.now().plusSeconds(expiresInSeconds)));
    }

    public void invalidate(String userId) {
        cache.remove(userId);
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}
}
