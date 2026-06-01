package com.gte619n.healthfitness.core.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findById(String userId);

    /**
     * Resolve many users by id in as few round-trips as possible, returning a
     * map keyed by userId (missing ids are simply absent). The default
     * implementation falls back to per-id {@link #findById} lookups so test
     * fakes work unchanged; the Firestore implementation overrides this with a
     * batched query to avoid N reads on first load.
     */
    default Map<String, User> findByIds(List<String> userIds) {
        Map<String, User> result = new LinkedHashMap<>();
        if (userIds == null) return result;
        for (String id : userIds) {
            if (id == null) continue;
            findById(id).ifPresent(u -> result.put(id, u));
        }
        return result;
    }

    Optional<User> findByHealthUserId(String healthUserId);
    void save(User user);
    void recordGoogleHealthConnection(String userId, GoogleHealthConnection connection);
    void clearGoogleHealthConnection(String userId);
    // Pass null to clear; an Integer cm value to set.
    void updateHeightCm(String userId, Integer heightCm);

    /**
     * Return the IDs of every top-level document in {@code users/}.
     *
     * Used by the daily SUSTAINED re-evaluation Cloud Run Job (IMPL-12
     * Phase 5) to iterate every user. Order is unspecified; callers must
     * not rely on it.
     */
    List<String> findAllUserIds();
}
