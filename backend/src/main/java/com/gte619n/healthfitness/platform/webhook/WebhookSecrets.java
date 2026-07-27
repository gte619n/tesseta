package com.gte619n.healthfitness.platform.webhook;

// Derives a per-client webhook signing secret from a single server master key
// (ADR-0020, D20). The secret is HMAC-SHA256(masterKey, "webhook:" + clientId),
// so it is stable per client, shown to the admin once at subscription time, and
// recomputed at delivery — never persisted, so there is no signing secret at
// rest to leak or to decrypt on the hot path.
public final class WebhookSecrets {

    private WebhookSecrets() {}

    public static String deriveSecret(String masterKey, String clientId) {
        return WebhookSigner.hmacHex("webhook:" + clientId, masterKey);
    }
}
