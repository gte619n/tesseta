package com.gte619n.healthfitness.core.goals.eval;

import com.gte619n.healthfitness.core.user.GoogleHealthConnection;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory {@link UserRepository} for core tests — a local copy
 * mirroring the {@code app}-module test fake. Only the methods exercised
 * by Goals evaluation tests are implemented with real semantics; the
 * Google-Health connection bookkeeping is preserved for completeness
 * since {@code save(User)} and {@code findAllUserIds()} are the primary
 * call sites here.
 */
final class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> store = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(store.get(userId));
    }

    @Override
    public Optional<User> findByHealthUserId(String healthUserId) {
        return store.values().stream()
            .filter(u -> u.googleHealth() != null
                && healthUserId.equals(u.googleHealth().healthUserId()))
            .findFirst();
    }

    @Override
    public void save(User user) {
        store.put(user.userId(), user);
    }

    @Override
    public void recordGoogleHealthConnection(String userId, GoogleHealthConnection connection) {
        User existing = store.get(userId);
        if (existing == null) {
            throw new IllegalStateException("Unknown user: " + userId);
        }
        store.put(userId, new User(
            existing.userId(),
            existing.email(),
            existing.displayName(),
            connection,
            existing.heightCm(),
            existing.createdAt(),
            Instant.now()
        ));
    }

    @Override
    public void clearGoogleHealthConnection(String userId) {
        User existing = store.get(userId);
        if (existing == null) return;
        store.put(userId, new User(
            existing.userId(),
            existing.email(),
            existing.displayName(),
            null,
            existing.heightCm(),
            existing.createdAt(),
            Instant.now()
        ));
    }

    @Override
    public void updateHeightCm(String userId, Integer heightCm) {
        User existing = store.get(userId);
        if (existing == null) return;
        store.put(userId, new User(
            existing.userId(),
            existing.email(),
            existing.displayName(),
            existing.googleHealth(),
            heightCm,
            existing.createdAt(),
            Instant.now()
        ));
    }

    @Override
    public List<String> findAllUserIds() {
        return new ArrayList<>(store.keySet());
    }
}
