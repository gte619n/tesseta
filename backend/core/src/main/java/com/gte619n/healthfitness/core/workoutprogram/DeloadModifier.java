package com.gte619n.healthfitness.core.workoutprogram;

/** How a prescription changes on the phase's deload week. */
public record DeloadModifier(Double setsMultiplier, Double intensityDelta) {}
