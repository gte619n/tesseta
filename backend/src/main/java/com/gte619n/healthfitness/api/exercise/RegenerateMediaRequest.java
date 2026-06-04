package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.exercise.DemoPhase;

/** {@code phase} null = regenerate all phases. */
public record RegenerateMediaRequest(String promptOverride, DemoPhase phase) {}
