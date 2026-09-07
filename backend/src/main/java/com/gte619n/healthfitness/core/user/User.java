package com.gte619n.healthfitness.core.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
    List<String> hiddenBiometrics
) {
    /** Normalize hiddenBiometrics to a non-null immutable list for every construction. */
    public User {
        hiddenBiometrics = hiddenBiometrics == null ? List.of() : List.copyOf(hiddenBiometrics);
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
