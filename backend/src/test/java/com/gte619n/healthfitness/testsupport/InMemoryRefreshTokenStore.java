package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.auth.RefreshTokenStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// In-memory RefreshTokenStore for unit tests (Firestore is off in the test
// profile). Mirrors the Firestore impl's semantics: store by tokenId, rotate
// atomically stamping the successor pointer, re-point, revoke by id, and burn
// the whole family for a user.
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, StoredRefreshToken> byId = new ConcurrentHashMap<>();

    @Override
    public void save(StoredRefreshToken token) {
        byId.put(token.tokenId(), token);
    }

    @Override
    public Optional<StoredRefreshToken> findById(String tokenId) {
        return Optional.ofNullable(byId.get(tokenId));
    }

    @Override
    public boolean tryMarkRotated(String tokenId, Instant rotatedAt, String successorId) {
        // computeIfPresent is atomic per key, so exactly one concurrent caller
        // observes a live token and performs the transition, stamping the
        // successor pointer in the same step.
        boolean[] won = {false};
        byId.computeIfPresent(tokenId, (id, t) -> {
            if (t.revoked()) return t; // already revoked — this caller lost
            won[0] = true;
            return new StoredRefreshToken(
                t.tokenId(), t.userId(), t.tokenHash(), t.createdAt(), t.expiresAt(), true,
                rotatedAt, successorId);
        });
        return won[0];
    }

    @Override
    public void repoint(String tokenId, String successorId) {
        // Move the successor pointer forward; leave revoked/rotatedAt intact.
        byId.computeIfPresent(tokenId, (id, t) -> new StoredRefreshToken(
            t.tokenId(), t.userId(), t.tokenHash(), t.createdAt(), t.expiresAt(), t.revoked(),
            t.rotatedAt(), successorId));
    }

    @Override
    public void markRevoked(String tokenId) {
        // Definitive (logout): revoked with no successor — the chain walk dead-ends.
        byId.computeIfPresent(tokenId, (id, t) -> new StoredRefreshToken(
            t.tokenId(), t.userId(), t.tokenHash(), t.createdAt(), t.expiresAt(), true, null, null));
    }

    @Override
    public void revokeAllForUser(String userId) {
        // Theft burn: revoke every still-live token. Live tokens carry no
        // successor, so the burned chain terminates at a dead tip and stays refused.
        byId.replaceAll((id, t) -> t.userId().equals(userId) && !t.revoked()
            ? new StoredRefreshToken(
                t.tokenId(), t.userId(), t.tokenHash(), t.createdAt(), t.expiresAt(), true,
                t.rotatedAt(), t.replacedBy())
            : t);
    }
}
