package com.gte619n.healthfitness.core.push;

import java.util.List;

/**
 * Per-user FCM device-token registry (IMPL-AND-20, D18). Tokens live at
 * {@code users/{uid}/fcmTokens/{deviceId}} — the device id is the doc key so a
 * refresh from the same device idempotently overwrites the same document.
 */
public interface FcmTokenRepository {

    /**
     * Register or refresh a token. {@code token.updatedAt} is ignored by durable
     * implementations, which stamp the write with the server clock.
     */
    void save(String userId, FcmToken token);

    /** Remove a device's token on sign-out. No-op if the device is unknown. */
    void delete(String userId, String deviceId);

    /** All currently-registered tokens for the user (any order). */
    List<FcmToken> findByUser(String userId);
}
