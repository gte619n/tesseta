package com.gte619n.healthfitness.core.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record User(
    String userId,
    String email,
    String displayName,
    GoogleHealthConnection googleHealth,
    Integer heightCm,
    Instant createdAt,
    Instant updatedAt,
    BiologicalSex biologicalSex,   // IMPL-PROG-01 M3: Mifflin-St Jeor cold-start
    LocalDate dateOfBirth,         // IMPL-PROG-01 M3: age for Mifflin-St Jeor
    WithingsConnection withings,   // withings-api: second health provider connection
    // biometrics: keys of metrics the user has hidden from the dashboard
    // (empty = all shown). Never null.
    List<String> hiddenBiometrics,
    // IMPL-MULTIUSER-01 P1.1: data-driven access control. Never null; defaults
    // to {USER}/ACTIVE so legacy docs and pre-roles callers behave unchanged.
    Set<UserRole> roles,
    UserStatus status,
    // IMPL-MULTIUSER-01 P1.7 (D13): when non-null, the account is scheduled for
    // hard deletion at this instant (status is set DISABLED at the same time).
    // Reactivating (status → ACTIVE) clears it. Nullable: null = not scheduled.
    Instant deletionScheduledAt
) {
    /**
     * Normalize the never-null fields for every construction: hiddenBiometrics →
     * immutable list, roles → immutable non-empty set (default {USER}), status →
     * ACTIVE when unset. This is also the backfill contract for legacy Firestore
     * docs that predate roles/status.
     */
    public User {
        hiddenBiometrics = hiddenBiometrics == null ? List.of() : List.copyOf(hiddenBiometrics);
        roles = (roles == null || roles.isEmpty()) ? Set.of(UserRole.USER) : Set.copyOf(roles);
        status = status == null ? UserStatus.ACTIVE : status;
    }

    /** True when this user holds the ADMIN role. */
    public boolean isAdmin() {
        return roles.contains(UserRole.ADMIN);
    }

    /**
     * Pre-deletionScheduledAt signature (IMPL-MULTIUSER-01 P1.7). Delegates with
     * no deletion schedule so every caller that predates offboarding compiles
     * unchanged.
     */
    public User(String userId, String email, String displayName, GoogleHealthConnection googleHealth,
                Integer heightCm, Instant createdAt, Instant updatedAt,
                BiologicalSex biologicalSex, LocalDate dateOfBirth, WithingsConnection withings,
                List<String> hiddenBiometrics, Set<UserRole> roles, UserStatus status) {
        this(userId, email, displayName, googleHealth, heightCm, createdAt, updatedAt,
            biologicalSex, dateOfBirth, withings, hiddenBiometrics, roles, status, null);
    }

    /**
     * Pre-roles/status signature (IMPL-MULTIUSER-01). Delegates with the default
     * {USER} role and ACTIVE status so every caller that predates access control
     * compiles unchanged.
     */
    public User(String userId, String email, String displayName, GoogleHealthConnection googleHealth,
                Integer heightCm, Instant createdAt, Instant updatedAt,
                BiologicalSex biologicalSex, LocalDate dateOfBirth, WithingsConnection withings,
                List<String> hiddenBiometrics) {
        this(userId, email, displayName, googleHealth, heightCm, createdAt, updatedAt,
            biologicalSex, dateOfBirth, withings, hiddenBiometrics,
            Set.of(UserRole.USER), UserStatus.ACTIVE, null);
    }

    /**
     * Pre-biometrics signature. Delegates with no hidden metrics so every
     * caller that predates the visibility pref compiles unchanged.
     */
    public User(String userId, String email, String displayName, GoogleHealthConnection googleHealth,
                Integer heightCm, Instant createdAt, Instant updatedAt,
                BiologicalSex biologicalSex, LocalDate dateOfBirth, WithingsConnection withings) {
        this(userId, email, displayName, googleHealth, heightCm, createdAt, updatedAt,
            biologicalSex, dateOfBirth, withings, List.of());
    }

    /**
     * Pre-Withings signature. Delegates with the Withings connection null and no
     * hidden metrics.
     */
    public User(String userId, String email, String displayName, GoogleHealthConnection googleHealth,
                Integer heightCm, Instant createdAt, Instant updatedAt,
                BiologicalSex biologicalSex, LocalDate dateOfBirth) {
        this(userId, email, displayName, googleHealth, heightCm, createdAt, updatedAt,
            biologicalSex, dateOfBirth, null, List.of());
    }

    /**
     * Pre-IMPL-PROG-01 signature. Delegates with the demographic fields, the
     * Withings connection null, and no hidden metrics.
     */
    public User(String userId, String email, String displayName, GoogleHealthConnection googleHealth,
                Integer heightCm, Instant createdAt, Instant updatedAt) {
        this(userId, email, displayName, googleHealth, heightCm, createdAt, updatedAt,
            null, null, null, List.of());
    }
}
