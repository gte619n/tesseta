package com.gte619n.healthfitness.core.device;

import java.time.Instant;

// The last time we ingested data attributed to a given source platform
// (e.g. "FITBIT", "WITHINGS") for a user. Persisted at
// users/{userId}/deviceSyncs/{platform} and refreshed on every successful
// ingestion (webhook hydration or backfill). Drives the device freshness
// indicators in the clients.
public record DeviceSync(
    String platform,
    Instant lastSyncedAt
) {}
