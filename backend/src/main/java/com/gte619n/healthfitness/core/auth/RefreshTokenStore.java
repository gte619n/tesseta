package com.gte619n.healthfitness.core.auth;

import java.time.Instant;
import java.util.Optional;

// Persistence port for opaque refresh tokens (ADR-0010). A refresh token's
// secret is never stored — only its SHA-256 hash — so a database leak cannot
// be replayed. Each token has a server-generated {@code tokenId} that the
// client carries alongside the secret, letting refresh be a single direct
// read instead of a scan.
//
// Refresh follows a *successor chain* (ADR-0019): rotation stamps the retired
// token with a pointer to its freshly-minted successor ({@code replacedBy}).
// Because a mobile client that never received a refresh response still holds the
// old token, a replay of a rotated token is honoured by walking the chain to its
// live tip and advancing that — no time window involved — so a benign retry
// succeeds however long the phone was offline. Only a token revoked with *no*
// successor (logout / a theft burn) or a chain whose tip is dead is refused.
public interface RefreshTokenStore {

    void save(StoredRefreshToken token);

    Optional<StoredRefreshToken> findById(String tokenId);

    // Rotation: the token has been superseded by a freshly-issued {@code
    // successorId}. Atomically flips revoked false->true AND stamps both
    // {@code rotatedAt} (informational) and {@code replacedBy = successorId}
    // only if the token is currently live; returns true iff THIS call performed
    // the transition.
    //
    // ATOMIC single-use guarantee: a plain read-then-write cannot provide this —
    // two concurrent refreshes can both observe revoked==false and both rotate,
    // minting two successor chains from one token. Implementations MUST perform
    // the check-and-set atomically (a Firestore transaction / a single atomic map
    // op). Returns false when the token is already revoked (lost the race, or
    // already used).
    boolean tryMarkRotated(String tokenId, Instant rotatedAt, String successorId);

    // Re-point an already-rotated token at a newer successor. Used when a stale
    // token is replayed repeatedly (each honoured replay advances the chain tip):
    // re-pointing keeps the next replay a single hop and always live-tipped, so
    // arbitrarily many retries of the same old token converge in O(1). Only
    // touches {@code replacedBy}; never un-revokes.
    void repoint(String tokenId, String successorId);

    // Definitive revocation with no successor (logout). Leaves {@code replacedBy}
    // null, so the chain walk finds a dead end and refuses any later replay: a
    // logged-out token can't be re-animated by a stray retry.
    void markRevoked(String tokenId);

    // Theft response / burn the family. Revokes every still-live token for the
    // user. Burned tokens gain no successor pointer, so the chain terminates at a
    // dead tip and stays refused.
    void revokeAllForUser(String userId);

    record StoredRefreshToken(
        String tokenId,
        String userId,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        boolean revoked,
        // Set when the token was retired by *rotation* (informational: when it
        // was rotated). Null for live tokens and for tokens revoked by logout or
        // a theft burn.
        Instant rotatedAt,
        // The tokenId that superseded this one. Set only by rotation; null for a
        // live token and for one revoked by logout / theft burn. The link that
        // makes a benign replay recoverable without a time window.
        String replacedBy
    ) {
        public boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }

        // A live, still-usable token: not revoked and not past its expiry.
        public boolean isLive(Instant now) {
            return !revoked && !isExpired(now);
        }
    }
}
