package com.gte619n.healthfitness.core.push;

import java.util.List;

/**
 * Outcome of a fan-out send (IMPL-AND-20, Phase 2).
 *
 * @param sentCount          number of messages the transport accepted.
 * @param unregisteredTokens token strings FCM reported as unregistered/invalid;
 *                           {@link SyncChangePublisher} prunes their registry
 *                           entries best-effort. Empty for the no-op transport.
 */
public record FcmSendResult(
    int sentCount,
    List<String> unregisteredTokens
) {

    public static FcmSendResult empty() {
        return new FcmSendResult(0, List.of());
    }

    public static FcmSendResult sent(int count) {
        return new FcmSendResult(count, List.of());
    }
}
