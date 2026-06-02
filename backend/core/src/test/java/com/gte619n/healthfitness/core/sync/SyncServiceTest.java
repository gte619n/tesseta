package com.gte619n.healthfitness.core.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SyncService} paging math against a deterministic
 * in-memory reader (IMPL-AND-20 Phase 1).
 */
class SyncServiceTest {

    /** Reader that holds a fixed, canonically ordered change list. */
    private static final class FakeReader implements SyncChangeReader {
        private final List<SyncChange> all;

        FakeReader(List<SyncChange> all) {
            this.all = new ArrayList<>(all);
            this.all.sort(SyncChange.CANONICAL_ORDER);
        }

        @Override
        public List<SyncChange> readChanges(
            String userId, SyncCursor since, int limit, SyncRecentWindow window) {
            // These tests exercise pure cursor/paging math; the recent-window
            // bound is unit-tested separately, so it is ignored here.
            List<SyncChange> out = new ArrayList<>();
            for (SyncChange c : all) {
                if (since == null || since.isBefore(c)) {
                    out.add(c);
                }
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        }
    }

    private static SyncChange active(long millis, String collection, String id) {
        return new SyncChange(collection, id, SyncStatus.ACTIVE, Instant.ofEpochMilli(millis), "doc");
    }

    @Test
    void pagesThroughEverythingExactlyOnceWithNoDupOrSkip() {
        // 5 changes, two sharing the same timestamp to exercise the tiebreak.
        List<SyncChange> all = List.of(
            active(10L, "bloodReadings", "a"),
            active(20L, "medications", "m1"),
            active(20L, "medications", "m2"),
            active(30L, "locations", "l1"),
            active(40L, "goals", "g1")
        );
        SyncService service = new SyncService(new FakeReader(all), false);

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            SyncPage page = service.page("u", cursor, 2);
            pages++;
            for (SyncChange c : page.changes()) {
                String key = c.collection() + "/" + c.id();
                assertThat(seen.add(key)).as("no duplicate: " + key).isTrue();
            }
            cursor = page.nextCursor();
            if (!page.hasMore()) {
                break;
            }
        }

        assertThat(seen).hasSize(5);
        assertThat(pages).isEqualTo(3); // 2 + 2 + 1
    }

    @Test
    void emptyResultLeavesCursorStableAndHasMoreFalse() {
        SyncService service = new SyncService(new FakeReader(List.of()), false);
        SyncPage page = service.page("u", null, 500);
        assertThat(page.changes()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.schemaVersion()).isEqualTo(SyncService.SYNC_SCHEMA_VERSION);
    }

    @Test
    void killSwitchPropagates() {
        SyncService service = new SyncService(new FakeReader(List.of()), true);
        assertThat(service.page("u", null, 10).killSwitch()).isTrue();
    }
}
