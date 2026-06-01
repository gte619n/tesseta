package com.gte619n.healthfitness.api.device;

import com.gte619n.healthfitness.core.device.Device;
import com.gte619n.healthfitness.core.device.DeviceSyncStatus;
import java.time.Instant;

public record DeviceResponse(
    String id,
    String name,
    String platform,
    Instant lastSyncedAt,
    DeviceSyncStatus status
) {
    public static DeviceResponse from(Device d) {
        return new DeviceResponse(d.id(), d.name(), d.platform(), d.lastSyncedAt(), d.status());
    }
}
