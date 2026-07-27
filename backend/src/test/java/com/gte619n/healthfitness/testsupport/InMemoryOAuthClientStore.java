package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.platform.OAuthClient;
import com.gte619n.healthfitness.core.platform.OAuthClientStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// In-memory OAuthClientStore for unit tests.
public class InMemoryOAuthClientStore implements OAuthClientStore {

    private final Map<String, OAuthClient> byId = new ConcurrentHashMap<>();

    @Override
    public void save(OAuthClient client) {
        byId.put(client.clientId(), client);
    }

    @Override
    public Optional<OAuthClient> findById(String clientId) {
        return Optional.ofNullable(byId.get(clientId));
    }

    @Override
    public List<OAuthClient> findAll() {
        return new ArrayList<>(byId.values());
    }
}
