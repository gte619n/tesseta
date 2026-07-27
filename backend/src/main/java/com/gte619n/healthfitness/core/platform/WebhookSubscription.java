package com.gte619n.healthfitness.core.platform;

import java.time.Instant;
import java.util.Set;

// A client's webhook subscription (ADR-0020, D21) — one per client, admin-managed.
// The HMAC signing secret is stored KMS-encrypted (we must retrieve it to sign
// each delivery, unlike client secrets which we only verify), reusing the same
// envelope-encryption cipher as Google Health refresh tokens.
public record WebhookSubscription(
    String clientId,
    String url,
    Set<String> eventTypes,
    byte[] secretCiphertext,
    byte[] dekCiphertext,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {}
