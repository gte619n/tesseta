package com.gte619n.healthfitness.core.push;

import java.util.List;

/**
 * Transport port for the FCM fan-out (IMPL-AND-20, Phase 2). The interface lives
 * in {@code core} so the publisher depends on it without dragging the Firebase
 * Admin SDK into the domain module; the real {@code FirebaseMessaging}-backed
 * implementation lives in {@code integrations} and is gated behind
 * {@code app.fcm.enabled}. A no-op implementation is the default so tests and
 * local-without-ADC runs need no credentials.
 */
public interface FcmSender {

    /**
     * Deliver a <b>data-only</b> sync message
     * {@code {"type":"sync","collections":"<csv>"}} to each token.
     *
     * <p>Implementations must not throw on per-token delivery failure; they
     * collect unregistered tokens in the result so the caller can prune them.
     *
     * @param tokens      the FCM registration tokens to address (already filtered
     *                    to exclude the origin device).
     * @param collections the changed collection names the client should pull.
     * @return a never-null result; the no-op transport returns {@link
     *         FcmSendResult#empty()}.
     */
    FcmSendResult sendSyncData(List<String> tokens, List<String> collections);
}
