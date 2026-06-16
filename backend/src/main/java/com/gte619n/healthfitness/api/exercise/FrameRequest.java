package com.gte619n.healthfitness.api.exercise;

/** Body for select/delete frame actions, keyed to the plan (IMPL-19). */
public record FrameRequest(String key, String imageUrl) {}
