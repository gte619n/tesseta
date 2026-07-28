package com.gte619n.healthfitness.api.googlehealth;

/**
 * Published the first time a user's Google Health connection transitions from
 * healthy to broken (a refresh-token exchange failed with a permanent auth
 * error). Fired only on the transition — not on every subsequent failure — so
 * a listener can notify the user exactly once per breakage.
 *
 * <p>Mirrors the {@code SyncChangedEvent} pattern in {@code core/push}: the
 * event is published after the broken flag is persisted, and listeners must
 * not let their side effects (e.g. push delivery) propagate back into the
 * token-exchange path.
 */
public record GoogleHealthConnectionBrokenEvent(String userId, String reason) {}
