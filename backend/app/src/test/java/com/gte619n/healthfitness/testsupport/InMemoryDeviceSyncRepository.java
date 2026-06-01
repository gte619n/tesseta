package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.device.DeviceSync;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Test fake. Keyed by (userId, platform); newest sync wins, matching the
// Firestore doc-per-platform layout.
public class InMemoryDeviceSyncRepository implements DeviceSyncRepository {

    private final Map<String, Map<String, Instant>> byUser = new ConcurrentHashMap<>();

    @Override
    public void recordSync(String userId, String platform, Instant syncedAt) {
        if (platform == null || platform.isBlank()) return;
        byUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(platform, syncedAt);
    }

    @Override
    public List<DeviceSync> findByUser(String userId) {
        return byUser.getOrDefault(userId, Map.of()).entrySet().stream()
            .map(e -> new DeviceSync(e.getKey(), e.getValue()))
            .toList();
    }
}
