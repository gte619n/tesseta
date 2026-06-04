package com.gte619n.healthfitness.core.auth;

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

    void markRevoked(String tokenId);

    // Theft response: when a rotated (already-revoked) token is presented, the
    // whole family for that user is burned so a stolen token can't be milked.
    void revokeAllForUser(String userId);

    record StoredRefreshToken(
        String tokenId,
        String userId,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        boolean revoked
    ) {
        public boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
