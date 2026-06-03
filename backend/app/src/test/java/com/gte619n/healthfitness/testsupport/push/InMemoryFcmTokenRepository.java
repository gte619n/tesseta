package com.gte619n.healthfitness.testsupport.push;

import com.gte619n.healthfitness.core.push.FcmToken;
import com.gte619n.healthfitness.core.push.FcmTokenRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link FcmTokenRepository} test double (IMPL-AND-20 Phase 2), wired
 * by {@code TestPersistenceConfig}. Keyed by {@code (userId, deviceId)} so a
 * refresh from the same device overwrites the same entry — mirroring the
 * Firestore impl where the device id is the doc key. {@code updatedAt} is
 * stamped eagerly on save.
 */
public class InMemoryFcmTokenRepository implements FcmTokenRepository {

    private record Key(String userId, String deviceId) {}

    private final Map<Key, FcmToken> tokens = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, FcmToken token) {
        if (token == null || token.deviceId() == null || token.deviceId().isBlank()
            || token.token() == null || token.token().isBlank()) {
            return;
        }
        tokens.put(new Key(userId, token.deviceId()),
            new FcmToken(token.deviceId(), token.token(), Instant.now()));
    }

    @Override
    public void delete(String userId, String deviceId) {
        if (deviceId == null) {
            return;
        }
        tokens.remove(new Key(userId, deviceId));
    }

    @Override
    public List<FcmToken> findByUser(String userId) {
        List<FcmToken> out = new ArrayList<>();
        for (Map.Entry<Key, FcmToken> e : tokens.entrySet()) {
            if (e.getKey().userId().equals(userId)) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    public void clear() {
        tokens.clear();
    }
}
