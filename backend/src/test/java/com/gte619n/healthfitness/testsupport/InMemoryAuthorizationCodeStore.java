package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.platform.AuthorizationCode;
import com.gte619n.healthfitness.core.platform.AuthorizationCodeStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// In-memory AuthorizationCodeStore for unit tests. consume() removes atomically
// (ConcurrentHashMap.remove) so a replayed code finds nothing — mirroring the
// Firestore transaction's single-use guarantee.
public class InMemoryAuthorizationCodeStore implements AuthorizationCodeStore {

    private final Map<String, AuthorizationCode> byHash = new ConcurrentHashMap<>();

    @Override
    public void save(AuthorizationCode code) {
        byHash.put(code.codeHash(), code);
    }

    @Override
    public Optional<AuthorizationCode> consume(String codeHash) {
        return Optional.ofNullable(byHash.remove(codeHash));
    }
}
