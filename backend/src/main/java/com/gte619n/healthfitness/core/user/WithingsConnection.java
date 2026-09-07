package com.gte619n.healthfitness.core.user;

import java.time.Instant;

// Per-user state for the Withings Health API integration. Populated after
// the user authorizes the user.metrics/user.activity scopes and the backend
// exchanges the resulting authorization code for tokens.
//
// refreshTokenCiphertext + dekCiphertext together hold an envelope-encrypted
// refresh token; see ADR-0004 (shares KmsTokenCipher with Google Health).
// Raw plaintext never appears in memory beyond the moments it's needed for a
// token exchange.
//
// Unlike Google Health, Withings ROTATES the refresh token on every exchange:
// each requesttoken/refresh call returns a fresh refresh token that supersedes
// the old one. WithingsAccessTokenService re-encrypts and re-persists it here
// after every successful refresh, so this ciphertext is expected to change
// over the life of the connection.
//
// brokenAt is null while the connection is healthy. It's stamped the first
// time a refresh fails with a permanent auth error (invalid_grant / the token
// is no longer usable) — the user must reconnect. brokenReason carries a short
// diagnostic for logs/UI. Reconnecting clears both.
public record WithingsConnection(
    String withingsUserId,
    byte[] refreshTokenCiphertext,
    byte[] dekCiphertext,
    Instant connectedAt,
    Instant brokenAt,
    String brokenReason
) {
    /** True when a refresh-token exchange has permanently failed and the user must reconnect. */
    public boolean needsReconnect() {
        return brokenAt != null;
    }
}
