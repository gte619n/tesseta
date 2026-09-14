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
    // OBS-006: optional metrics for FCM fan-out visibility. Absent in the focused
    // unit test (no context); instrumentation is then a WARN-only no-op.
    private final io.micrometer.core.instrument.MeterRegistry meters;

    /** Spring-wired ctor: metrics injected when actuator is present. */
    @org.springframework.beans.factory.annotation.Autowired
    public SyncChangePublisher(
        FcmTokenRepository tokens,
        FcmSender sender,
        org.springframework.beans.factory.ObjectProvider<
            io.micrometer.core.instrument.MeterRegistry> meterRegistry) {
        this.tokens = tokens;
        this.sender = sender;
        this.meters = meterRegistry.getIfAvailable();
    }

    /** Test ctor without metrics. */
    public SyncChangePublisher(FcmTokenRepository tokens, FcmSender sender) {
        this.tokens = tokens;
        this.sender = sender;
        this.meters = null;
    }

    private void count(String metric, String userId) {
        if (meters != null) {
            meters.counter(metric).increment();
        }
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
            // OBS-006: a user with ZERO registered tokens means EVERY sync push
            // for them is silently dropped — this was the invisible dead-push
            // class (the prod FCM-cert mismatch emptied the registry for
            // everyone). WARN + count so the outage is observable, not silent.
            log.log(Level.WARNING, "FCM fan-out found NO tokens for user=" + event.userId()
                + " collections=" + event.collections() + " — push silently dropped");
            count("fcm.fanout.no_tokens", event.userId());
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

        // OBS-006: batch-send failures were debug-gated and thus invisible. A
        // recipient that neither delivered nor was reported unregistered is a
        // silent failure — WARN + count with the tallies so partial fan-out
        // outages surface. (unregistered tokens are handled by the prune below.)
        int attempted = recipients.size();
        int delivered = result != null ? result.sentCount() : 0;
        int unregistered = result != null ? result.unregisteredTokens().size() : attempted;
        int failed = Math.max(0, attempted - delivered - unregistered);
        if (failed > 0) {
            log.log(Level.WARNING, "FCM fan-out partial failure for user=" + event.userId()
                + " attempted=" + attempted + " delivered=" + delivered
                + " unregistered=" + unregistered + " failed=" + failed);
            count("fcm.fanout.batch_failures", event.userId());
        }

        // Prune tokens FCM rejected as unregistered/invalid (best-effort).
        FcmTokenPruning.prune(tokens, log, event.userId(), recipients, result);
    }
}
