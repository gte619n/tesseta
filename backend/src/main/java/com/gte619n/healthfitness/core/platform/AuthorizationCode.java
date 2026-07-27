package com.gte619n.healthfitness.core.platform;

import java.time.Instant;
import java.util.Set;

// A single-use authorization code (ADR-0020), bound to the user who consented,
// the client, the exact redirect URI, the granted scopes, and the PKCE
// challenge. Stored in `oauthCodes` keyed by the SHA-256 hash of the code — the
// code itself (like a refresh secret) never touches the database, so a store
// leak cannot be redeemed.
//
// The code is short-lived (app.platform.code-ttl, minutes) and consumed
// atomically at the token endpoint, so it can be exchanged exactly once.
public record AuthorizationCode(
    String codeHash,
    String clientId,
    String userId,
    String userEmail,
    String userName,
    String redirectUri,
    Set<String> scopes,
    String codeChallenge,
    String codeChallengeMethod,
    Instant createdAt,
    Instant expiresAt
) {
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
