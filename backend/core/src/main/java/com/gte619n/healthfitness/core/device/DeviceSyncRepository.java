package com.gte619n.healthfitness.core.device;

import java.time.Instant;
import java.util.List;

public interface DeviceSyncRepository {

    // Idempotent upsert: records that `platform` synced data for `userId`
    // at `syncedAt`. Later calls overwrite earlier ones (newest wins).
    void recordSync(String userId, String platform, Instant syncedAt);

    List<DeviceSync> findByUser(String userId);
}
