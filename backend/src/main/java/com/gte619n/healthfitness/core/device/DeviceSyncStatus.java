package com.gte619n.healthfitness.core.device;

import java.time.Duration;
import java.time.Instant;

// Traffic-light freshness of a device, derived from how recently it last
// synced data. GREEN = fresh, YELLOW = getting stale, RED = stale / never.
public enum DeviceSyncStatus {
    GREEN,
    YELLOW,
    RED;

    private static final Duration FRESH = Duration.ofHours(24);
    private static final Duration STALE = Duration.ofDays(7);

    public static DeviceSyncStatus fromLastSynced(Instant lastSynced, Instant now) {
        if (lastSynced == null) {
            return RED;
        }
        Duration age = Duration.between(lastSynced, now);
        if (age.compareTo(FRESH) < 0) {
            return GREEN;
        }
        if (age.compareTo(STALE) < 0) {
            return YELLOW;
        }
        return RED;
    }
}
