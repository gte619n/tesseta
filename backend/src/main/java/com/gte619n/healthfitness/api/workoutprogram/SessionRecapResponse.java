package com.gte619n.healthfitness.api.workoutprogram;

/**
 * IMPL-COACH: the AI post-workout recap for one completed session. {@code recap}
 * is null when the session isn't completed yet or the coach is unavailable.
 */
public record SessionRecapResponse(String recap) {}
