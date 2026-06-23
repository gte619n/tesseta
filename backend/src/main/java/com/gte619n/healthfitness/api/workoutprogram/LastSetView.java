package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;

/**
 * One previously-performed set, slimmed for the live coach's "same as last
 * time" prefill (IMPL-COACH PR2). Only the fields the logger pre-fills: load,
 * reps, and (for timed holds) duration.
 */
public record LastSetView(Double weightLbs, Integer reps, Integer durationSeconds) {

    public static LastSetView from(LoggedSet set) {
        return new LastSetView(set.weightLbs(), set.reps(), set.durationSeconds());
    }
}
