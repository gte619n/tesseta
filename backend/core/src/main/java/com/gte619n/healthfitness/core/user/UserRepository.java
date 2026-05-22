package com.gte619n.healthfitness.core.user;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findById(String userId);
    Optional<User> findByHealthUserId(String healthUserId);
    void save(User user);
    void recordGoogleHealthConnection(String userId, GoogleHealthConnection connection);
    void clearGoogleHealthConnection(String userId);
    // Pass null to clear; an Integer cm value to set.
    void updateHeightCm(String userId, Integer heightCm);
}
