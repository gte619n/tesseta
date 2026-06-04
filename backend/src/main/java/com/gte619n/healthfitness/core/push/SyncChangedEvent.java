package com.gte619n.healthfitness.core.push;

import java.util.List;

/**
 * Application event published after a successful in-scope write (IMPL-AND-20,
 * Phase 2). {@link SyncChangePublisher} consumes it and fans a silent FCM data
 * message out to the user's other devices so they pull the delta.
 *
 * @param userId          the owning user whose devices should pull.
 * @param collections     the logical collection names that changed (the Android
 *                        client maps these to its Room mirror tables, e.g.
 *                        {@code "bloodReadings"}, {@code "medications"}).
 * @param originDeviceId  the device that produced the change ({@code
 *                        X-HF-Origin-Device}); suppressed from the fan-out (D18).
 *                        {@code null} for server-originated changes (e.g. the
 *                        Google Health webhook) — those fan out to ALL tokens.
 */
public record SyncChangedEvent(
    String userId,
    List<String> collections,
    String originDeviceId
) {}
