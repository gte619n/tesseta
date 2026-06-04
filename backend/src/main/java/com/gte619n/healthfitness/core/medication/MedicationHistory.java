package com.gte619n.healthfitness.core.medication;

import java.time.Instant;

/**
 * History entry for medication changes (dose/frequency/schedule changes).
 * Stored in: users/{userId}/medications/{medicationId}/history/{historyId}
 */
public record MedicationHistory(
    String historyId,
    String userId,
    String medicationId,
    ChangeType changeType,
    String previousValue,       // JSON representation of old value
    String newValue,            // JSON representation of new value
    Instant changedAt,
    String notes                // (nullable)
) {}
