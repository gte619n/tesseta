package com.gte619n.healthfitness.core.device;

import java.time.Instant;

// A connected device as surfaced to the clients: a friendly name, the raw
// source platform it maps to, when it last synced, and the traffic-light
// status computed from that timestamp.
public record Device(
    String id,
    String name,
    String platform,
    Instant lastSyncedAt,
    DeviceSyncStatus status
) {}
