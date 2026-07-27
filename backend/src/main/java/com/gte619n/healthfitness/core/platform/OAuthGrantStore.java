package com.gte619n.healthfitness.core.platform;

import java.util.List;
import java.util.Optional;

// Persistence port for standing user->client consent grants (ADR-0020).
public interface OAuthGrantStore {

    // Upsert: a re-authorization with additional scopes replaces the record.
    void save(OAuthGrant grant);

    Optional<OAuthGrant> find(String userId, String clientId);

    List<OAuthGrant> findByUser(String userId);

    // Every user who has granted this client — drives webhook fan-out (ADR-0020).
    List<OAuthGrant> findByClient(String clientId);

    void delete(String userId, String clientId);
}
