package com.gte619n.healthfitness.core.user;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findById(String userId);
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
