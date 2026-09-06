package com.gte619n.healthfitness.core.user;

import java.time.Instant;
import java.time.LocalDate;

public record User(
    String userId,
    String email,
    String displayName,
    GoogleHealthConnection googleHealth,
    Integer heightCm,
    Instant createdAt,
    Instant updatedAt,
    BiologicalSex biologicalSex,   // IMPL-PROG-01 M3: Mifflin-St Jeor cold-start
    LocalDate dateOfBirth          // IMPL-PROG-01 M3: age for Mifflin-St Jeor
) {
    /**
     * Pre-IMPL-PROG-01 signature. Delegates with the demographic fields null so
     * every existing caller (mapper, in-memory fake, tests) compiles unchanged.
     */
    public User(String userId, String email, String displayName, GoogleHealthConnection googleHealth,
                Integer heightCm, Instant createdAt, Instant updatedAt) {
        this(userId, email, displayName, googleHealth, heightCm, createdAt, updatedAt, null, null);
    }
}
