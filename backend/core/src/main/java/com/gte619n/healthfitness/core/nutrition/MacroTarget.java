package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A user's daily macro goal. Targets have history; the active one is the
 * target with the greatest {@code effectiveFrom <= today}.
 */
public record MacroTarget(
    String userId,
    String targetId,
    Macros macros,
    LocalDate effectiveFrom,
    Instant createdAt,
    Instant updatedAt
) {}
