package com.gte619n.healthfitness.integrations.push;

import com.gte619n.healthfitness.core.push.FcmSendResult;
import com.gte619n.healthfitness.core.push.FcmSender;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real FCM transport (IMPL-AND-20, Phase 2) — sends a <b>data-only</b> silent
 * push via the Firebase Admin SDK. Gated behind {@code app.fcm.enabled=true};
 * when disabled (tests, local without ADC) the core {@code NoOpFcmSender} is
 * used instead, so no credentials are required to run.
 *
 * <p>Authenticates via Application Default Credentials (no service-account JSON
 * key): the {@link FirebaseMessaging} bean is built from a {@code FirebaseApp}
 * the {@code app} module initializes with ADC + metadata project id (see the
 * spec Prerequisites; runtime SA holds {@code roles/firebasecloudmessaging.admin}).
 *
 * <p>Per-token failures do not throw; tokens FCM reports as {@code UNREGISTERED}
 * / {@code INVALID_ARGUMENT} are collected so the publisher prunes the registry.
 */
@Component
@ConditionalOnProperty(name = "app.fcm.enabled", havingValue = "true")
public class FirebaseMessagingFcmSender implements FcmSender {

    private static final Logger log = LoggerFactory.getLogger(FirebaseMessagingFcmSender.class);

    /** Max tokens per multicast send (Firebase Admin SDK limit). */
    private static final int MAX_BATCH = 500;

    private final FirebaseMessaging messaging;

    public FirebaseMessagingFcmSender(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    @Override
    public FcmSendResult sendSyncData(List<String> tokens, List<String> collections) {
        if (tokens == null || tokens.isEmpty()) {
            return FcmSendResult.empty();
        }
        // Data-only payload — no `notification` block — so the client wakes a
        // background worker without surfacing a user-visible notification.
        Map<String, String> data = Map.of(
            "type", "sync",
            "collections", String.join(",", collections == null ? List.of() : collections));

        int sent = 0;
        List<String> unregistered = new ArrayList<>();

        for (int start = 0; start < tokens.size(); start += MAX_BATCH) {
            List<String> batch = tokens.subList(start, Math.min(start + MAX_BATCH, tokens.size()));
            MulticastMessage message = MulticastMessage.builder()
                .putAllData(data)
                .addAllTokens(batch)
                .build();
            try {
                BatchResponse response = messaging.sendEachForMulticast(message);
                sent += response.getSuccessCount();
                collectUnregistered(batch, response, unregistered);
            } catch (FirebaseMessagingException e) {
                // Whole-batch failure (e.g. auth/transport) — log and move on; the
                // client's periodic floor recovers the missed pull.
                log.warn("FCM multicast send failed for {} token(s): {}", batch.size(), e.toString());
            }
        }
        return new FcmSendResult(sent, unregistered);
    }

    private static void collectUnregistered(
        List<String> batch, BatchResponse response, List<String> unregistered) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse r = responses.get(i);
            if (r.isSuccessful()) {
                continue;
            }
            FirebaseMessagingException ex = r.getException();
            MessagingErrorCode code = ex == null ? null : ex.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                unregistered.add(batch.get(i));
            }
        }
    }
}
