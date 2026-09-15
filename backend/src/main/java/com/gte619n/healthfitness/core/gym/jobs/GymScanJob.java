package com.gte619n.healthfitness.core.gym.jobs;

/**
 * IMPL-GYM-003: the durable unit of gym-video analysis work. Carries only
 * identifiers + the object ref — never the video bytes — so it serializes small
 * onto the queue (mirrors {@code NutritionJob}).
 */
public record GymScanJob(
    String userId,
    String locationId,
    String scanId,
    String videoRef,
    String mimeType
) {}
