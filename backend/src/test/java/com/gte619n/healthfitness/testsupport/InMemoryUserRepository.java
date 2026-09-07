package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.user.GoogleHealthConnection;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.core.user.WithingsConnection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Test fake. Replaces the Firestore-backed UserRepository in unit-test
// Spring contexts so SecurityConfigTest / DevHeaderAuthTest /
// WhoAmIControllerTest can run without an emulator.
public class InMemoryUserRepository implements UserRepository {

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
    public Optional<User> findByWithingsUserId(String withingsUserId) {
        return store.values().stream()
            .filter(u -> u.withings() != null
                && withingsUserId.equals(u.withings().withingsUserId()))
            .findFirst();
    }

    @Override
    public void save(User user) {
        store.put(user.userId(), user);
    }

    @Override
    public void recordGoogleHealthConnection(String userId, GoogleHealthConnection connection) {
        User existing = require(userId);
        store.put(userId, existing.googleHealth() == connection ? existing
            : withGoogleHealth(existing, connection));
    }

    @Override
    public void markGoogleHealthBroken(String userId, String reason) {
        User existing = store.get(userId);
        if (existing == null || existing.googleHealth() == null) return;
        GoogleHealthConnection gh = existing.googleHealth();
        store.put(userId, withGoogleHealth(existing, new GoogleHealthConnection(
            gh.healthUserId(), gh.refreshTokenCiphertext(), gh.dekCiphertext(),
            gh.connectedAt(), Instant.now(), reason)));
    }

    @Override
    public void clearGoogleHealthConnection(String userId) {
        User existing = store.get(userId);
        if (existing == null) return;
        store.put(userId, withGoogleHealth(existing, null));
    }

    @Override
    public void recordWithingsConnection(String userId, WithingsConnection connection) {
        User existing = require(userId);
        // Mirror the Firestore impl: a (re)connect clears any broken flag, and a
        // rotation with connectedAt==null inherits the original connect time.
        WithingsConnection prior = existing.withings();
        Instant connectedAt = connection.connectedAt() != null ? connection.connectedAt()
            : prior != null && prior.connectedAt() != null ? prior.connectedAt() : Instant.now();
        store.put(userId, withWithings(existing, new WithingsConnection(
            connection.withingsUserId(), connection.refreshTokenCiphertext(),
            connection.dekCiphertext(), connectedAt, null, null)));
    }

    @Override
    public void markWithingsBroken(String userId, String reason) {
        User existing = store.get(userId);
        if (existing == null || existing.withings() == null) return;
        WithingsConnection w = existing.withings();
        store.put(userId, withWithings(existing, new WithingsConnection(
            w.withingsUserId(), w.refreshTokenCiphertext(), w.dekCiphertext(),
            w.connectedAt(), Instant.now(), reason)));
    }

    @Override
    public void clearWithingsConnection(String userId) {
        User existing = store.get(userId);
        if (existing == null) return;
        store.put(userId, withWithings(existing, null));
    }

    @Override
    public void updateHeightCm(String userId, Integer heightCm) {
        User existing = store.get(userId);
        if (existing == null) return;
        store.put(userId, new User(
            existing.userId(), existing.email(), existing.displayName(), existing.googleHealth(),
            heightCm, existing.createdAt(), Instant.now(),
            existing.biologicalSex(), existing.dateOfBirth(), existing.withings()));
    }

    @Override
    public void updateDemographics(
        String userId,
        com.gte619n.healthfitness.core.user.BiologicalSex biologicalSex,
        java.time.LocalDate dateOfBirth) {
        User existing = store.get(userId);
        if (existing == null) return;
        store.put(userId, new User(
            existing.userId(), existing.email(), existing.displayName(), existing.googleHealth(),
            existing.heightCm(), existing.createdAt(), Instant.now(),
            biologicalSex, dateOfBirth, existing.withings()));
    }

    @Override
    public List<String> findAllUserIds() {
        return new ArrayList<>(store.keySet());
    }

    private User require(String userId) {
        User existing = store.get(userId);
        if (existing == null) {
            throw new IllegalStateException("Unknown user: " + userId);
        }
        return existing;
    }

    // Rebuild preserving every field except the Google Health connection.
    private static User withGoogleHealth(User u, GoogleHealthConnection gh) {
        return new User(u.userId(), u.email(), u.displayName(), gh, u.heightCm(),
            u.createdAt(), Instant.now(), u.biologicalSex(), u.dateOfBirth(), u.withings());
    }

    // Rebuild preserving every field except the Withings connection.
    private static User withWithings(User u, WithingsConnection w) {
        return new User(u.userId(), u.email(), u.displayName(), u.googleHealth(), u.heightCm(),
            u.createdAt(), Instant.now(), u.biologicalSex(), u.dateOfBirth(), w);
    }
}
