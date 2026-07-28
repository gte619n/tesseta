package com.gte619n.healthfitness.core.user;

import java.time.Instant;

// Per-user state for the Google Health API integration. Populated after
// the user grants the health_metrics_and_measurements.readonly scope and
// the web client forwards their refresh token to the backend.
//
// refreshTokenCiphertext + dekCiphertext together hold an envelope-
// encrypted refresh token; see ADR-0004. Raw plaintext never appears in
// memory beyond the moments it's needed for an OAuth exchange.
//
// brokenAt is null while the connection is healthy. It's stamped the first
// time a refresh-token exchange fails with a permanent auth error
// (invalid_grant) — the token is dead and the user must reconnect.
// brokenReason carries a short diagnostic for logs/UI. Reconnecting clears
// both.
public record GoogleHealthConnection(
    String healthUserId,
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
