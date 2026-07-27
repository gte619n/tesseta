package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.platform.OAuthGrant;
import com.gte619n.healthfitness.core.platform.OAuthGrantStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// In-memory OAuthGrantStore for unit tests.
public class InMemoryOAuthGrantStore implements OAuthGrantStore {

    private final Map<String, OAuthGrant> byKey = new ConcurrentHashMap<>();

    private static String key(String userId, String clientId) {
        return userId + "__" + clientId;
    }

    @Override
    public void save(OAuthGrant grant) {
        byKey.put(key(grant.userId(), grant.clientId()), grant);
    }

    @Override
    public Optional<OAuthGrant> find(String userId, String clientId) {
        return Optional.ofNullable(byKey.get(key(userId, clientId)));
    }

    @Override
    public List<OAuthGrant> findByUser(String userId) {
        List<OAuthGrant> out = new ArrayList<>();
        for (OAuthGrant g : byKey.values()) {
            if (g.userId().equals(userId)) out.add(g);
        }
        return out;
    }

    @Override
    public List<OAuthGrant> findByClient(String clientId) {
        List<OAuthGrant> out = new ArrayList<>();
        for (OAuthGrant g : byKey.values()) {
            if (g.clientId().equals(clientId)) out.add(g);
        }
        return out;
    }

    @Override
    public void delete(String userId, String clientId) {
        byKey.remove(key(userId, clientId));
    }
}
