package com.gte619n.healthfitness.platform;

import com.google.cloud.firestore.Firestore;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

// Attributable audit trail for third-party data access (ADR-0020, decision D8).
// Health data raises the stakes: every /v1 read of a user's labs/medications must
// be traceable to a client. Each event emits a structured log line AND a
// best-effort append to the `platformAuditLog` Firestore collection — the write
// is fire-and-forget (never awaited) so a slow or failing audit write can never
// slow down or fail the API request it describes.
//
// Not gated on the platform flag: it depends only on an optional Firestore, so
// it wires everywhere (a no-op persist when Firestore is absent, e.g. tests).
@Component
public class PlatformAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("platform.audit");
    private static final String COLLECTION = "platformAuditLog";

    private final ObjectProvider<Firestore> firestore;

    public PlatformAuditLogger(ObjectProvider<Firestore> firestore) {
        this.firestore = firestore;
    }

    public void record(String clientId, String userId, String scope,
                       String method, String path, int status) {
        log.info("v1 access client={} user={} scope=\"{}\" {} {} -> {}",
            clientId, userId, scope, method, path, status);

        Firestore fs = firestore.getIfAvailable();
        if (fs == null) {
            return;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("clientId", clientId);
            body.put("userId", userId);
            body.put("scope", scope);
            body.put("method", method);
            body.put("path", path);
            body.put("status", status);
            body.put("at", Instant.now().toString());
            // Fire-and-forget: do not await the ApiFuture.
            fs.collection(COLLECTION).document().set(body);
        } catch (RuntimeException e) {
            // Auditing must never break the request path.
            log.warn("failed to persist audit event (client={} user={})", clientId, userId, e);
        }
    }
}
