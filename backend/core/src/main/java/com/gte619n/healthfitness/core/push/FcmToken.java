package com.gte619n.healthfitness.core.push;

import java.time.Instant;

/**
 * One FCM registration token for a single device, stored at
 * {@code users/{uid}/fcmTokens/{deviceId}} (IMPL-AND-20, D18).
 *
 * @param deviceId  the stable client-supplied device identifier (the Firestore
 *                  doc id), also the {@code originDeviceId} a write carries so
 *                  the producing device can be suppressed from its own fan-out.
 * @param token     the FCM registration token the messaging transport addresses.
 * @param updatedAt server timestamp of the last register/refresh; null until the
 *                  server has stamped it (the in-memory fake stamps eagerly).
 */
public record FcmToken(
    String deviceId,
    String token,
    Instant updatedAt
) {}
