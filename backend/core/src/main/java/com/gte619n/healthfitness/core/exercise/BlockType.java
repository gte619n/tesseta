package com.gte619n.healthfitness.core.exercise;

/**
 * The kind of section an exercise can occupy inside a workout day. Shared
 * between the Exercise catalog (IMPL-14, {@code Exercise.suitableBlockTypes})
 * and Workout Programs (IMPL-15, {@code Block.type}).
 */
public enum BlockType {
    WARMUP, MOBILITY, CARDIO, MAIN, ACCESSORY, CORE, COOLDOWN, STRETCH
}
