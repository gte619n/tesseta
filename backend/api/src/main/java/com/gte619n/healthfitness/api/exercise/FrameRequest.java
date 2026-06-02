package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.exercise.DemoPhase;

/** Body for select/delete frame actions. */
public record FrameRequest(DemoPhase phase, String imageUrl) {}
