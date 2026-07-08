package com.gte619n.healthfitness.api.exercise;

/**
 * Body of {@code POST /api/exercises/{exerciseId}/flag-frame} (#9): the owner
 * marks a specific demo frame as bad. {@code frameKey} identifies the frame
 * within the exercise's plan; {@code note} is an optional free-text reason.
 */
public record FlagFrameRequest(String frameKey, String note) {}
