package com.gte619n.healthfitness.core.platform;

import java.util.List;
import java.util.Optional;

// Persistence port for registered OAuth clients (ADR-0020). Top-level
// `oauthClients/{clientId}`.
public interface OAuthClientStore {

    void save(OAuthClient client);

    Optional<OAuthClient> findById(String clientId);

    List<OAuthClient> findAll();
}
