package com.gte619n.healthfitness.core.access;

import java.time.Instant;

/**
 * One entry in the invite allowlist (IMPL-MULTIUSER-01 P1.3, decision D2).
 * {@code emailLower} is the normalized (lowercased, trimmed) email that gates
 * signup; {@code addedBy} is the admin userId who granted it.
 */
public record EmailAllowlistEntry(
    String emailLower,
    String addedBy,
    Instant addedAt
) {}
