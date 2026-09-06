package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;

/**
 * Published by {@code WorkoutSessionCompletionService} after a COMPLETED upsert
 * and its metric fan-out. Spring delivers it synchronously in the same thread
 * (IMPL-PROG-01 D22), so the progression session loop runs and the updated
 * next-session prescriptions are persisted before the completion call returns.
 */
public record SessionCompletedEvent(String userId, ScheduledWorkout session) {}
