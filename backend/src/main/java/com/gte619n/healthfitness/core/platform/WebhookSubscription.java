package com.gte619n.healthfitness.core.platform;

import java.time.Instant;
import java.util.Set;

// A client's webhook subscription (ADR-0020, D21) — one per client, admin-managed.
// The HMAC signing secret is NOT stored: it is derived deterministically from a
// server master key + the clientId (see WebhookSecrets), so there is no secret
// at rest to leak or decrypt, and delivery recomputes it on demand.
public record WebhookSubscription(
    String clientId,
    String url,
    Set<String> eventTypes,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {}
