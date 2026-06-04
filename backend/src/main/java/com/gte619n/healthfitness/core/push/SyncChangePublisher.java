package com.gte619n.healthfitness.core.push;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges a {@link SyncChangedEvent} to the FCM fan-out (IMPL-AND-20, D18).
 *
 * <p>On each event it loads the user's registered tokens, drops the token that
 * belongs to the {@code originDeviceId} (so the producing device does not
 * redundantly self-pull), and asks the {@link FcmSender} to deliver a
 * <b>data-only</b> message {@code {"type":"sync","collections":[...]}} to the
 * remaining tokens. Tokens FCM reports as unregistered are pruned best-effort.
 *
 * <p>Fan-out failures never propagate back to the write path: the event is
 * published <em>after</em> the persistent write has committed, and a missed push
 * is recovered by the client's periodic sync floor (D10).
 */
@Component
public class SyncChangePublisher {

    private static final Logger log = System.getLogger(SyncChangePublisher.class.getName());

    /** Data-message type discriminator the Android client switches on. */
    public static final String MESSAGE_TYPE = "sync";

    private final FcmTokenRepository tokens;
    private final FcmSender sender;

    public SyncChangePublisher(FcmTokenRepository tokens, FcmSender sender) {
        this.tokens = tokens;
        this.sender = sender;
    }

    @EventListener
    public void onSyncChanged(SyncChangedEvent event) {
        try {
            fanOut(event);
        } catch (RuntimeException e) {
            // A write must never fail because a push could not be delivered.
            log.log(Level.WARNING, "Sync fan-out failed for user=" + event.userId()
                + " collections=" + event.collections() + ": " + e);
        }
    }

    private void fanOut(SyncChangedEvent event) {
        List<FcmToken> all = tokens.findByUser(event.userId());
        if (all.isEmpty()) {
            return;
        }
        // Suppress the originating device (D18). A null originDeviceId (server-
        // originated change, e.g. Google Health webhook) fans out to everyone.
        List<FcmToken> recipients = new ArrayList<>();
        for (FcmToken t : all) {
            if (event.originDeviceId() != null && event.originDeviceId().equals(t.deviceId())) {
                continue;
            }
            recipients.add(t);
        }
        if (recipients.isEmpty()) {
            return;
        }

        List<String> tokenValues = recipients.stream().map(FcmToken::token).toList();
        FcmSendResult result = sender.sendSyncData(tokenValues, event.collections());

        // Prune tokens FCM rejected as unregistered/invalid (best-effort). Map the
        // rejected token string back to its device id and delete the registration.
        if (result != null && !result.unregisteredTokens().isEmpty()) {
            for (FcmToken t : recipients) {
                if (result.unregisteredTokens().contains(t.token())) {
                    try {
                        tokens.delete(event.userId(), t.deviceId());
                    } catch (RuntimeException e) {
                        log.log(Level.DEBUG,
                            "Failed pruning stale token device=" + t.deviceId() + ": " + e);
                    }
                }
            }
        }
    }
}
