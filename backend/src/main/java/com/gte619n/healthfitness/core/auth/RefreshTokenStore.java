package com.gte619n.healthfitness.core.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// Persistence port for opaque refresh tokens (ADR-0010). A refresh token's
// secret is never stored — only its SHA-256 hash — so a database leak cannot
// be replayed. Each token has a server-generated {@code tokenId} that the
// client carries alongside the secret, letting refresh be a single direct
// read instead of a scan.
public interface RefreshTokenStore {

    void save(StoredRefreshToken token);

    Optional<StoredRefreshToken> findById(String tokenId);

    // Rotation: the token has been superseded by a freshly-issued successor.
    // Stamps {@code rotatedAt} so a benign retry of a rotated token (a refresh
    // whose response was lost in flight) can be told apart from a genuine replay
    // attack by the reuse-grace window — see SessionTokenService#refresh.
    void markRotated(String tokenId, Instant rotatedAt);

    // Definitive revocation with no successor (logout). Unlike markRotated this
    // leaves {@code rotatedAt} null, so the token is never reuse-grace eligible:
    // a logged-out token can't be re-animated by a stray retry.
    void markRevoked(String tokenId);

    // Theft response: when a rotated token is replayed *after* the reuse-grace
    // window, the whole family for that user is burned so a stolen token can't
    // be milked. Burned tokens carry no {@code rotatedAt}, so they are likewise
    // not reuse-grace eligible.
    void revokeAllForUser(String userId);

    record StoredRefreshToken(
        String tokenId,
        String userId,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        boolean revoked,
        // Set only when the token was retired by *rotation* (it has a legitimate
        // successor). Null for live tokens and for tokens revoked by logout or a
        // theft burn. The anchor for the reuse-grace window.
        Instant rotatedAt
    ) {
        public boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }

        // A rotated token replayed within {@code grace} of its rotation is almost
        // always a benign client retry of a refresh whose response never arrived,
        // not theft. Only rotation sets rotatedAt, so logout/theft-burned tokens
        // never qualify.
        public boolean withinReuseGrace(Instant now, Duration grace) {
            return revoked && rotatedAt != null && !now.isAfter(rotatedAt.plus(grace));
        }
    }
}
