package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.auth.RefreshTokenStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// In-memory RefreshTokenStore for unit tests (Firestore is off in the test
// profile). Mirrors the Firestore impl's semantics: store by tokenId, revoke
// by id, and burn the whole family for a user.
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
    public void markRevoked(String tokenId) {
        byId.computeIfPresent(tokenId, (id, t) -> new StoredRefreshToken(
            t.tokenId(), t.userId(), t.tokenHash(), t.createdAt(), t.expiresAt(), true));
    }

    @Override
    public void revokeAllForUser(String userId) {
        byId.replaceAll((id, t) -> t.userId().equals(userId)
            ? new StoredRefreshToken(
                t.tokenId(), t.userId(), t.tokenHash(), t.createdAt(), t.expiresAt(), true)
            : t);
    }
}
