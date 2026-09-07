package com.gte619n.healthfitness.api.withings;

/**
 * Published the first time a user's Withings connection transitions from
 * healthy to broken (a refresh failed with a permanent auth error). Fired only
 * on the transition — not on every subsequent failure — so a listener can
 * notify the user exactly once per breakage. Parallels
 * {@code GoogleHealthConnectionBrokenEvent}.
 */
public record WithingsConnectionBrokenEvent(String userId, String reason) {}
