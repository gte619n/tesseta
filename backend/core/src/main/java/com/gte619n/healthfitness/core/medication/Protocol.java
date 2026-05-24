package com.gte619n.healthfitness.core.medication;

import java.time.Instant;
import java.util.List;

/**
 * Named protocol grouping for medications (e.g., "TRT Stack").
 * Stored in: users/{userId}/protocols/{protocolId}
 */
public record Protocol(
    String userId,
    String protocolId,
    String name,                    // "TRT Stack"
    String description,             // (nullable)
    List<String> medicationIds,     // References to medications in this protocol
    Instant createdAt,
    Instant updatedAt
) {}
