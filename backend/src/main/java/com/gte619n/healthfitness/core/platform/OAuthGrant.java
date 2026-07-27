package com.gte619n.healthfitness.core.platform;

import java.time.Instant;
import java.util.Set;

// A standing record that a user consented to a client for a set of scopes
// (ADR-0020). Backs the first-party "Connected Apps" screen (list + revoke) and
// lets a re-authorization skip the consent prompt when the app requests scopes
// the user already granted. Stored in `oauthGrants` keyed by userId+clientId.
public record OAuthGrant(
    String userId,
    String clientId,
    Set<String> scopes,
    Instant grantedAt,
    Instant updatedAt
) {}
