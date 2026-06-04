package com.gte619n.healthfitness.core.workout;

import java.time.Instant;

public record Workout(
    String userId,
    String workoutId,
    String activityType,
    String locationId,
    Instant startTime,
    Instant endTime,
    String source,
    Instant createdAt,
    Instant updatedAt
) {}
