package com.gte619n.healthfitness.core.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeviceSyncStatusTest {

    private static final Instant NOW = Instant.parse("2026-05-31T12:00:00Z");

    @Test
    void greenWhenSyncedWithinADay() {
        assertThat(DeviceSyncStatus.fromLastSynced(NOW.minus(Duration.ofHours(2)), NOW))
            .isEqualTo(DeviceSyncStatus.GREEN);
    }

    @Test
    void yellowWhenSyncedWithinAWeek() {
        assertThat(DeviceSyncStatus.fromLastSynced(NOW.minus(Duration.ofDays(3)), NOW))
            .isEqualTo(DeviceSyncStatus.YELLOW);
    }

    @Test
    void redWhenStale() {
        assertThat(DeviceSyncStatus.fromLastSynced(NOW.minus(Duration.ofDays(10)), NOW))
            .isEqualTo(DeviceSyncStatus.RED);
    }

    @Test
    void redWhenNeverSynced() {
        assertThat(DeviceSyncStatus.fromLastSynced(null, NOW)).isEqualTo(DeviceSyncStatus.RED);
    }

    @Test
    void boundaryExactlyOneDayIsYellow() {
        assertThat(DeviceSyncStatus.fromLastSynced(NOW.minus(Duration.ofHours(24)), NOW))
            .isEqualTo(DeviceSyncStatus.YELLOW);
    }
}
