package com.gte619n.healthfitness.core.platform;

import java.time.Instant;
import java.util.Optional;

// Persistence port for third-party refresh tokens (ADR-0020). Mirrors the
// first-party RefreshTokenStore (ADR-0019) rotation contract — atomic
// single-use rotation, a successor pointer for benign-replay recovery, and a
// family burn — plus a per-client revoke used when a user disconnects an app
// from the Connected Apps screen.
public interface PlatformRefreshTokenStore {

    void save(PlatformRefreshToken token);

    Optional<PlatformRefreshToken> findById(String tokenId);

    // Atomically flip revoked false->true and stamp rotatedAt + replacedBy iff
    // the token is currently live. Returns true iff THIS call performed the
    // transition. Must be atomic (a Firestore transaction) — see ADR-0019.
    boolean tryMarkRotated(String tokenId, Instant rotatedAt, String successorId);

    // Move an already-rotated token's successor pointer forward so a repeatedly
    // replayed stale token stays a single hop from the live tip.
    void repoint(String tokenId, String successorId);

    // Definitive revocation with no successor (single token). The chain walk
    // dead-ends, so a later replay is refused.
    void markRevoked(String tokenId);

    // Theft response: revoke every still-live token for the user across all
    // clients.
    void revokeAllForUser(String userId);

    // Disconnect one app: revoke every still-live token for this user+client.
    // Backs the Connected Apps revoke so removing an app immediately kills its
    // background access.
    void revokeForUserAndClient(String userId, String clientId);
}
