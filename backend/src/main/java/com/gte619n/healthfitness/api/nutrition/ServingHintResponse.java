package com.gte619n.healthfitness.api.nutrition;

/**
 * Wire response for the lazy "typical serving" hint endpoint. {@code hint} is null
 * when no explanation could be generated (analyzer unavailable, or the entry has
 * no weight/name to describe) — the client then shows nothing.
 */
public record ServingHintResponse(String hint) {}
