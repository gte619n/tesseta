package com.gte619n.healthfitness.core.platform;

import java.time.Instant;
import java.util.Set;

// A third-party refresh token (ADR-0020). Reuses the successor-chain rotation
// design of the first-party session tokens (ADR-0019) — single-use, stored only
// as a hash, a benign replay recovered by walking `replacedBy` to the live tip —
// but carries the extra `clientId` and `scopes` a delegated grant needs, so a
// rotated token keeps issuing access tokens for the same app and the same
// consented scopes. Lives in its own `platformRefreshTokens` collection,
// separate from the first-party `refreshTokens`.
public record PlatformRefreshToken(
    String tokenId,
    String userId,
    String clientId,
    Set<String> scopes,
    String tokenHash,
    Instant createdAt,
    Instant expiresAt,
    boolean revoked,
    Instant rotatedAt,
    String replacedBy
) {
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isLive(Instant now) {
        return !revoked && !isExpired(now);
    }
}
