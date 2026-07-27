package com.gte619n.healthfitness.platform.webhook;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// Signs webhook payloads with HMAC-SHA256 (ADR-0020, D20). The subscriber
// recomputes HMAC-SHA256(rawBody, sharedSecret) and compares to the
// `X-Tesseta-Signature: sha256=<hex>` header — the de-facto outbound-webhook
// scheme (Stripe/GitHub), trivially verifiable with just the shared secret.
public final class WebhookSigner {

    public static final String HEADER = "X-Tesseta-Signature";

    private WebhookSigner() {}

    public static String signatureHeader(String body, String secret) {
        return "sha256=" + hmacHex(body, secret);
    }

    public static String hmacHex(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute webhook HMAC", e);
        }
    }
}
