package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.platform.PlatformRefreshToken;
import com.gte619n.healthfitness.core.platform.PlatformRefreshTokenStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// In-memory PlatformRefreshTokenStore for unit tests. Mirrors the Firestore
// impl's semantics (atomic rotation via computeIfPresent, successor pointer,
// per-user and per-user+client burn).
public class InMemoryPlatformRefreshTokenStore implements PlatformRefreshTokenStore {

    private final Map<String, PlatformRefreshToken> byId = new ConcurrentHashMap<>();

    @Override
    public void save(PlatformRefreshToken token) {
        byId.put(token.tokenId(), token);
    }

    @Override
    public Optional<PlatformRefreshToken> findById(String tokenId) {
        return Optional.ofNullable(byId.get(tokenId));
    }

    @Override
    public boolean tryMarkRotated(String tokenId, Instant rotatedAt, String successorId) {
        boolean[] won = {false};
        byId.computeIfPresent(tokenId, (id, t) -> {
            if (t.revoked()) return t; // lost the race / already used
            won[0] = true;
            return new PlatformRefreshToken(
                t.tokenId(), t.userId(), t.clientId(), t.scopes(), t.tokenHash(),
                t.createdAt(), t.expiresAt(), true, rotatedAt, successorId);
        });
        return won[0];
    }

    @Override
    public void repoint(String tokenId, String successorId) {
        byId.computeIfPresent(tokenId, (id, t) -> new PlatformRefreshToken(
            t.tokenId(), t.userId(), t.clientId(), t.scopes(), t.tokenHash(),
            t.createdAt(), t.expiresAt(), t.revoked(), t.rotatedAt(), successorId));
    }

    @Override
    public void markRevoked(String tokenId) {
        byId.computeIfPresent(tokenId, (id, t) -> new PlatformRefreshToken(
            t.tokenId(), t.userId(), t.clientId(), t.scopes(), t.tokenHash(),
            t.createdAt(), t.expiresAt(), true, null, null));
    }

    @Override
    public void revokeAllForUser(String userId) {
        byId.replaceAll((id, t) -> t.userId().equals(userId) && !t.revoked()
            ? revokedCopy(t) : t);
    }

    @Override
    public void revokeForUserAndClient(String userId, String clientId) {
        byId.replaceAll((id, t) ->
            t.userId().equals(userId) && t.clientId().equals(clientId) && !t.revoked()
                ? revokedCopy(t) : t);
    }

    private static PlatformRefreshToken revokedCopy(PlatformRefreshToken t) {
        return new PlatformRefreshToken(
            t.tokenId(), t.userId(), t.clientId(), t.scopes(), t.tokenHash(),
            t.createdAt(), t.expiresAt(), true, t.rotatedAt(), t.replacedBy());
    }
}
