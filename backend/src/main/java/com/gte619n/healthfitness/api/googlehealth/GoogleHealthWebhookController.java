package com.gte619n.healthfitness.api.googlehealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataType;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Webhook endpoint for Google Health API push notifications.
//
// Two distinct request kinds arrive here:
//   1. Domain-verification probes during subscriber create/update —
//      Google sends one POST with the secret in the Authorization header
//      and one without; we respond 200 / 401 respectively so Google can
//      confirm our auth filter actually filters.
//   2. Real notifications about UPSERT or DELETE events. We verify the
//      Authorization header, parse the body, then route on dataType:
//      body-composition types (weight, body-fat) go to
//      WebhookHandlerService; day-grained vitals/activity (steps, sleep,
//      daily-resting-heart-rate, daily-heart-rate-variability) go to
//      DailyMetricWebhookHandler. Both hydrate via REST and write Firestore.
//
// The endpoint is public-by-design (no JWT auth filter); the
// Authorization header is the shared secret, not a bearer token.
@RestController
@RequestMapping("/api/webhooks/google-health")
public class GoogleHealthWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GoogleHealthWebhookController.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String configuredSecret;
    private final WebhookHandlerService handler;
    private final DailyMetricWebhookHandler dailyHandler;
    private final GoogleHealthSignatureVerifier signatureVerifier;

    public GoogleHealthWebhookController(
        @Value("${app.googlehealth.webhook-secret:}") String configuredSecret,
        WebhookHandlerService handler,
        DailyMetricWebhookHandler dailyHandler,
        GoogleHealthSignatureVerifier signatureVerifier
    ) {
        this.configuredSecret = configuredSecret;
        this.handler = handler;
        this.dailyHandler = dailyHandler;
        this.signatureVerifier = signatureVerifier;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
        HttpServletRequest request,
        @RequestBody(required = false) String body
    ) {
        String provided = request.getHeader("Authorization");
        if (!isAuthorized(provided)) {
            log.warn("Webhook rejected — Authorization mismatch");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (body == null || body.isBlank()) {
            // Domain-verification authorized probe with no body: return 200.
            return ResponseEntity.ok().build();
        }

        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (java.io.IOException e) {
            log.warn("Webhook body not JSON, treating as probe: {}", e.getMessage());
            return ResponseEntity.ok().build();
        }

        // Real notifications wrap their fields in a top-level "data"
        // envelope (see https://developers.google.com/health/webhooks); the
        // domain-verification probe posts {"type":"verification"} with no
        // data object. Unwrap once so the rest of the method sees the
        // notification fields directly, whether or not the envelope is
        // present (older/flat payloads pass through unchanged).
        JsonNode payload = node.hasNonNull("data") ? node.path("data") : node;

        // Google's domain-verification probes carry a JSON body that lacks
        // the notification fields (or has empty placeholders). Treat any
        // request without a real healthUserId + populated interval as a
        // probe and respond 200.
        if (!looksLikeRealNotification(payload)) {
            log.info("Webhook probe accepted (no notification payload)");
            return ResponseEntity.ok().build();
        }

        // Real notifications carry an ECDSA signature over the raw body. When
        // enforcement is enabled, a missing/invalid signature is rejected
        // (Google retries 5xx/4xx for 7 days). Probes are exempt — they're
        // already past the check above. Off by default; the shared secret is
        // the baseline gate.
        if (signatureVerifier.enforced()
            && !signatureVerifier.verify(
                request.getHeader(GoogleHealthSignatureVerifier.SIGNATURE_HEADER),
                body.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Webhook rejected — invalid {}",
                GoogleHealthSignatureVerifier.SIGNATURE_HEADER);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            dispatch(payload);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.error("Webhook notification handling failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static boolean looksLikeRealNotification(JsonNode node) {
        if (node.path("healthUserId").asText("").isEmpty()) return false;
        if (node.path("dataType").asText("").isEmpty()) return false;
        JsonNode interval = node.path("intervals").path(0);
        if (intervalTime(interval, "startTime").isEmpty()) return false;
        if (intervalTime(interval, "endTime").isEmpty()) return false;
        return true;
    }

    // Real notifications express each interval as a nested
    // physicalTimeInterval (UTC RFC-3339 instants); we read that. The bare
    // startTime/endTime fallback keeps the parser tolerant of the older,
    // flat interval shape.
    private static String intervalTime(JsonNode interval, String field) {
        String nested = interval.path("physicalTimeInterval").path(field).asText("");
        return nested.isEmpty() ? interval.path(field).asText("") : nested;
    }

    private boolean isAuthorized(String header) {
        if (configuredSecret.isBlank()) return false;
        if (header == null) return false;
        return constantTimeEquals(header.getBytes(StandardCharsets.UTF_8),
            configuredSecret.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    // Parse the common envelope once, then route on dataType. A single
    // dataType string is either a daily-metric type (steps, sleep,
    // daily-resting-heart-rate, daily-heart-rate-variability) or a
    // body-composition type (weight, body-fat) — never both. An unrecognized type is
    // logged and acked (200) rather than 500'd: Google retries 5xx for 7
    // days, and there's nothing to gain by retrying a type we don't model.
    private void dispatch(JsonNode node) {
        String healthUserId = node.path("healthUserId").asText();
        String dataTypeRaw = node.path("dataType").asText();
        String operationRaw = node.path("operation").asText("UPSERT");
        JsonNode interval = node.path("intervals").path(0);
        Instant from = Instant.parse(intervalTime(interval, "startTime"));
        Instant to = Instant.parse(intervalTime(interval, "endTime"));

        Optional<DailyMetricDataType> dailyType = DailyMetricDataType.tryFromApiName(dataTypeRaw);
        if (dailyType.isPresent()) {
            DailyMetricWebhookHandler.Operation op =
                DailyMetricWebhookHandler.Operation.valueOf(operationRaw);
            dailyHandler.handle(new DailyMetricWebhookHandler.Notification(
                healthUserId, dailyType.get(), op, from, to));
            return;
        }

        Optional<GoogleHealthDataType> bodyType = GoogleHealthDataType.tryFromApiName(dataTypeRaw);
        if (bodyType.isPresent()) {
            WebhookHandlerService.Operation op =
                WebhookHandlerService.Operation.valueOf(operationRaw);
            handler.handle(new WebhookHandlerService.Notification(
                healthUserId, bodyType.get(), op, from, to));
            return;
        }

        log.warn("Webhook notification for unmodeled dataType={} — acknowledged, not stored",
            dataTypeRaw);
    }
}
