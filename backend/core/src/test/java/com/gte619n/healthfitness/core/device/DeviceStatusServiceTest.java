package com.gte619n.healthfitness.core.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class DeviceStatusServiceTest {

    private static final String USER = "u-1";

    @Test
    void returnsOnlyDevicesThatHaveSynced() {
        FakeRepo repo = new FakeRepo();
        // No syncs recorded for this user — no placeholder devices.
        DeviceStatusService svc = new DeviceStatusService(repo);
        assertThat(svc.devicesFor(USER)).isEmpty();
    }

    @Test
    void mapsPlatformToFriendlyNameAndStatus() {
        FakeRepo repo = new FakeRepo();
        repo.recordSync(USER, "FITBIT", Instant.now().minus(Duration.ofHours(1)));
        repo.recordSync(USER, "WITHINGS", Instant.now().minus(Duration.ofDays(10)));

        DeviceStatusService svc = new DeviceStatusService(repo);
        List<Device> devices = svc.devicesFor(USER);

        assertThat(devices).extracting(Device::name)
            .containsExactlyInAnyOrder("Fitbit", "Withings");
        Device fitbit = devices.stream().filter(d -> d.platform().equals("FITBIT")).findFirst().orElseThrow();
        assertThat(fitbit.status()).isEqualTo(DeviceSyncStatus.GREEN);
        Device withings = devices.stream().filter(d -> d.platform().equals("WITHINGS")).findFirst().orElseThrow();
        assertThat(withings.status()).isEqualTo(DeviceSyncStatus.RED);
    }

    @Test
    void sortsMostRecentlySyncedFirst() {
        FakeRepo repo = new FakeRepo();
        repo.recordSync(USER, "WITHINGS", Instant.now().minus(Duration.ofDays(2)));
        repo.recordSync(USER, "FITBIT", Instant.now().minus(Duration.ofMinutes(5)));

        DeviceStatusService svc = new DeviceStatusService(repo);
        assertThat(svc.devicesFor(USER)).extracting(Device::platform)
            .containsExactly("FITBIT", "WITHINGS");
    }

    @Test
    void titleCasesUnknownPlatform() {
        assertThat(DeviceStatusService.displayName("polar_h10")).isEqualTo("Polar H10");
        assertThat(DeviceStatusService.displayName("UNKNOWN")).isEqualTo("Unknown device");
    }

    private static final class FakeRepo implements DeviceSyncRepository {
        private final Map<String, Map<String, Instant>> byUser = new ConcurrentHashMap<>();

        @Override
        public void recordSync(String userId, String platform, Instant syncedAt) {
            byUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(platform, syncedAt);
        }

        @Override
        public List<DeviceSync> findByUser(String userId) {
            List<DeviceSync> out = new ArrayList<>();
            byUser.getOrDefault(userId, Map.of())
                .forEach((p, t) -> out.add(new DeviceSync(p, t)));
            return out;
        }
    }
}
