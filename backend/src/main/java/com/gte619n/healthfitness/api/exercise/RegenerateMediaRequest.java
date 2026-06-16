package com.gte619n.healthfitness.api.exercise;

/** {@code key} null = regenerate all frames in the plan (IMPL-19). */
public record RegenerateMediaRequest(String promptOverride, String key) {}
