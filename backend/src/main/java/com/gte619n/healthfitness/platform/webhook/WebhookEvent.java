package com.gte619n.healthfitness.platform.webhook;

import java.time.Instant;

// A single "thin" webhook event (ADR-0020, D19). The payload carries only the
// event type, the affected user, when it happened, and a pointer (`href`) to the
// /v1 resource the subscriber should GET for the full, current data. Thin events
// keep webhook bodies small, avoid duplicating the read model, and mean a
// subscriber always fetches authoritative data through the scoped, audited /v1
// API rather than trusting webhook contents.
public record WebhookEvent(
    String type,
    String userId,
    Instant occurredAt,
    String resourceType,
    String resourceId,
    String href
) {}
