package com.gte619n.healthfitness.core.push;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Sends a user-visible FCM notification (title/body + routing {@code data}) to
 * every device registered for a user, with the same registry bookkeeping the
 * silent sync fan-out gets from {@link SyncChangePublisher}: tokens FCM reports
 * as unregistered are pruned best-effort.
 *
 * <p>Unlike a missed sync ping — which the client's periodic floor recovers — a
 * dropped notification is simply gone, so every dead-end is logged loudly
 * rather than silently returned from: an empty token registry, a send that
 * reached zero devices, and a transport failure. (An empty registry for weeks
 * of "working" pushes is exactly the failure mode that hid the 2026-09 FCM
 * cert-mismatch outage.)
 *
 * <p>Shared by the adjust/leftover review notifiers and the Google Health /
 * Withings reconnect notifiers so none of them re-implements — or silently
 * skips — this bookkeeping. {@link #send} never throws: callers sit on event
 * paths where the durable state is already persisted, and a missed push must
 * not fail the operation that produced it.
 */
@Component
public class UserNotificationPusher {

    private static final Logger log = System.getLogger(UserNotificationPusher.class.getName());

    private final FcmTokenRepository tokens;
    private final FcmSender sender;

    public UserNotificationPusher(FcmTokenRepository tokens, FcmSender sender) {
        this.tokens = tokens;
        this.sender = sender;
    }

    /**
     * Deliver the notification to all of {@code userId}'s registered devices.
     *
     * @param kind short label for logs, conventionally the {@code data.type}
     *             the client routes on (e.g. {@code adjust-review}).
     */
    public void send(String kind, String userId, String title, String body, Map<String, String> data) {
        try {
            List<FcmToken> all = tokens.findByUser(userId);
            if (all.isEmpty()) {
                log.log(Level.WARNING,
                    kind + " push dropped: no FCM tokens registered for user=" + userId
                        + " (device never registered, or all tokens were pruned)");
                return;
            }
            List<String> tokenValues = all.stream().map(FcmToken::token).toList();
            FcmSendResult result = sender.sendNotification(tokenValues, title, body, data);
            FcmTokenPruning.prune(tokens, log, userId, all, result);
            if (result == null || result.sentCount() == 0) {
                log.log(Level.WARNING, kind + " push reached 0 of " + all.size()
                    + " registered device(s) for user=" + userId);
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, kind + " push failed for user=" + userId + ": " + e);
        }
    }
}
