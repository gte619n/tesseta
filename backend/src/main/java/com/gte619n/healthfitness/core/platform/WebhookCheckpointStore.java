package com.gte619n.healthfitness.core.platform;

import java.time.Instant;
import java.util.Optional;

// Per-(client, user) delivery checkpoint (ADR-0020, D19): the timestamp through
// which events have been delivered. The poller only emits events that occurred
// after this and advances it on a successful delivery, giving at-least-once
// delivery without missing changes across restarts.
public interface WebhookCheckpointStore {

    Optional<Instant> find(String clientId, String userId);

    void save(String clientId, String userId, Instant deliveredThrough);
}
