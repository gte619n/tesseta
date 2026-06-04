package com.gte619n.healthfitness.core.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SyncStatusTest {

    @Test
    void hasActiveAndArchivedValues() {
        assertThat(SyncStatus.values())
            .containsExactly(SyncStatus.ACTIVE, SyncStatus.ARCHIVED);
    }

    @Test
    void roundTripsByName() {
        assertThat(SyncStatus.valueOf("ACTIVE")).isEqualTo(SyncStatus.ACTIVE);
        assertThat(SyncStatus.valueOf("ARCHIVED")).isEqualTo(SyncStatus.ARCHIVED);
    }
}
