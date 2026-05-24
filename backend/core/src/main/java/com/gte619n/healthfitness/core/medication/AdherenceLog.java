package com.gte619n.healthfitness.core.medication;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Daily adherence log for a medication.
 * Stored in: users/{userId}/medications/{medicationId}/adherence/{date}
 */
public record AdherenceLog(
    String userId,
    String medicationId,
    LocalDate date,             // "2026-05-23"
    List<DoseLog> doses,        // [{ window: MORNING, takenAt: ..., dose: 100 }]
    String notes                // (nullable)
) {}
