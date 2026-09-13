package com.gte619n.healthfitness.core.push;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * Best-effort prune of registry entries whose tokens FCM rejected as
 * unregistered/invalid — extracted from {@link SyncChangePublisher} so the
 * notification path ({@link UserNotificationPusher}) prunes the same way.
 * Maps each rejected token string back to its device id and deletes that
 * registration; individual delete failures are swallowed (the next send just
 * reports the token unregistered again).
 */
final class FcmTokenPruning {

    private FcmTokenPruning() {}

    static void prune(FcmTokenRepository tokens, Logger log, String userId,
        List<FcmToken> recipients, FcmSendResult result) {
        if (result == null || result.unregisteredTokens().isEmpty()) {
            return;
        }
        for (FcmToken t : recipients) {
            if (result.unregisteredTokens().contains(t.token())) {
                try {
                    tokens.delete(userId, t.deviceId());
                    log.log(Level.INFO,
                        "Pruned unregistered FCM token device=" + t.deviceId() + " user=" + userId);
                } catch (RuntimeException e) {
                    log.log(Level.DEBUG,
                        "Failed pruning stale token device=" + t.deviceId() + ": " + e);
                }
            }
        }
    }
}
