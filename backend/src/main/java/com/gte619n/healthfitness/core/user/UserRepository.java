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

    /**
     * Stamp the user's Google Health connection as broken (a refresh-token
     * exchange failed permanently), setting brokenAt/brokenReason without
     * touching the encrypted token fields. Reconnecting via
     * {@link #recordGoogleHealthConnection} clears these again.
     */
    void markGoogleHealthBroken(String userId, String reason);

    /**
     * Resolve a user by their Withings numeric user id (stored as a string).
     * Used by the Withings webhook handler to map an inbound {@code userid}
     * notification back to the owning app user.
     */
    Optional<User> findByWithingsUserId(String withingsUserId);

    /**
     * Persist (or replace) the user's Withings connection. Because Withings
     * rotates the refresh token on every exchange, this is called both at
     * connect time and after each successful refresh to store the new token.
     * A (re)connect also clears any prior broken flag.
     */
    void recordWithingsConnection(String userId, WithingsConnection connection);

    void clearWithingsConnection(String userId);

    /**
     * Stamp the user's Withings connection as broken (a refresh failed
     * permanently), setting brokenAt/brokenReason without touching the
     * encrypted token fields. Reconnecting via
     * {@link #recordWithingsConnection} clears these again.
     */
    void markWithingsBroken(String userId, String reason);

    // Pass null to clear; an Integer cm value to set.
    void updateHeightCm(String userId, Integer heightCm);

    /**
     * Replace the set of biometric metric keys the user has hidden from the
     * dashboard (empty = all shown). Field-scoped merge; leaves everything else
     * intact. Synced per-account so visibility follows the user across devices.
     */
    void updateHiddenBiometrics(String userId, List<String> hiddenBiometrics);

    /**
     * Set the Mifflin-St Jeor demographics (IMPL-PROG-01 M3). A null argument
     * clears that field. Field-scoped merge — leaves everything else intact.
     */
    void updateDemographics(String userId, BiologicalSex biologicalSex, java.time.LocalDate dateOfBirth);

    /**
     * Return the IDs of every top-level document in {@code users/}.
     *
     * Used by the daily SUSTAINED re-evaluation Cloud Run Job (IMPL-12
     * Phase 5) to iterate every user. Order is unspecified; callers must
     * not rely on it.
     */
    List<String> findAllUserIds();

    // IMPL-MULTIUSER-01 P1.1/P1.5 — access-control administration. Defaulted so
    // the many existing test fakes compile unchanged; the Firestore impl and the
    // in-memory fake override them.

    /** Replace the account status (field-scoped merge). */
    default void updateStatus(String userId, UserStatus status) {
        throw new UnsupportedOperationException("updateStatus not supported");
    }

    /** Replace the role set (field-scoped merge). */
    default void updateRoles(String userId, java.util.Set<UserRole> roles) {
        throw new UnsupportedOperationException("updateRoles not supported");
    }

    /**
     * Admin list/search. When {@code query} is non-blank, prefix-matches on the
     * lowercased email. When {@code status} is non-null, filters to that status.
     * Returns at most {@code limit} users. Intended for the admin console only.
     */
    default List<User> search(String query, UserStatus status, int limit) {
        return List.of();
    }

    /** Users holding the ADMIN role (for last-admin protection). */
    default List<User> findAdmins() {
        return List.of();
    }

    // IMPL-MULTIUSER-01 P1.7 (D13) — export-then-delete offboarding. Defaulted so
    // the existing test fakes compile unchanged; the Firestore impl + in-memory
    // fake override them.

    /**
     * Schedule the account for hard deletion: set {@code status = DISABLED} and
     * stamp {@code deletionScheduledAt} (field-scoped merge). A null
     * {@code scheduledAt} clears the schedule (used on reactivation).
     */
    default void updateDeletionSchedule(String userId, UserStatus status, java.time.Instant scheduledAt) {
        throw new UnsupportedOperationException("updateDeletionSchedule not supported");
    }

    /**
     * Users whose {@code deletionScheduledAt} is at or before {@code cutoff} — the
     * purge job's work list (grace window elapsed). Default empty for stubs.
     */
    default List<User> findDeletionDue(java.time.Instant cutoff) {
        return List.of();
    }

    /** Hard-delete the {@code users/{uid}} document. Subcollection cascade is a limitation. */
    default void deleteById(String userId) {
        throw new UnsupportedOperationException("deleteById not supported");
    }
}
