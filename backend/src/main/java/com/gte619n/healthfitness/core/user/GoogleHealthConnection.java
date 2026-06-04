package com.gte619n.healthfitness.core.user;

import java.time.Instant;

// Per-user state for the Google Health API integration. Populated after
// the user grants the health_metrics_and_measurements.readonly scope and
// the web client forwards their refresh token to the backend.
//
// refreshTokenCiphertext + dekCiphertext together hold an envelope-
// encrypted refresh token; see ADR-0004. Raw plaintext never appears in
// memory beyond the moments it's needed for an OAuth exchange.
public record GoogleHealthConnection(
    String healthUserId,
    byte[] refreshTokenCiphertext,
    byte[] dekCiphertext,
    Instant connectedAt
) {}
