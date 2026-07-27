package com.gte619n.healthfitness.platform;

import java.util.Set;

// The result of a successful token grant (ADR-0020). `refreshToken` is null when
// the grant did not include offline_access, so a monitoring app that wants
// background access must request that scope explicitly.
public record IssuedTokens(
    String accessToken,
    long accessTokenExpiresInSeconds,
    String refreshToken,
    Set<String> scopes
) {}
