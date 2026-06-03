package com.gte619n.healthfitness.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the opaque sync cursor (IMPL-AND-20 Phase 1). */
class SyncCursorTest {

    @Test
    void encodeDecodeRoundTrips() {
        SyncCursor cursor = new SyncCursor(1_717_352_651_482L, "medications", "8f3c-uuid");
        SyncCursor decoded = SyncCursor.decode(cursor.encode());
        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void roundTripsSubcollectionIdWithSlashes() {
        // Subcollection identity encodes parent ids in the id with '/'.
        SyncCursor cursor = new SyncCursor(42L, "medications/adherence", "med-1/2026-05-30");
        SyncCursor decoded = SyncCursor.decode(cursor.encode());
        assertThat(decoded).isEqualTo(cursor);
        assertThat(decoded.id()).isEqualTo("med-1/2026-05-30");
    }

    @Test
    void blankOrNullDecodesToNullForFullSync() {
        assertThat(SyncCursor.decode(null)).isNull();
        assertThat(SyncCursor.decode("")).isNull();
        assertThat(SyncCursor.decode("   ")).isNull();
    }

    @Test
    void malformedCursorThrows() {
        assertThatThrownBy(() -> SyncCursor.decode("!!!not-base64!!!"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isBeforeOrdersByTimestampThenCollectionThenId() {
        SyncCursor base = new SyncCursor(100L, "bloodReadings", "b");

        // Strictly later timestamp ⇒ after the cursor.
        assertThat(base.isBefore(change(200L, "aaa", "a"))).isTrue();
        // Strictly earlier timestamp ⇒ not after.
        assertThat(base.isBefore(change(50L, "zzz", "z"))).isFalse();
        // Same timestamp, later collection ⇒ after.
        assertThat(base.isBefore(change(100L, "medications", "a"))).isTrue();
        // Same timestamp, earlier collection ⇒ not after ("bloodReadings" >
        // "bloodPressure" lexicographically — 'R' < 's' is false, so pick a
        // collection that genuinely sorts before it).
        assertThat(base.isBefore(change(100L, "bloodPressure", "z"))).isFalse();
        // Same timestamp + collection, later id ⇒ after.
        assertThat(base.isBefore(change(100L, "bloodReadings", "c"))).isTrue();
        // Same timestamp + collection + id (the cursor itself) ⇒ not after.
        assertThat(base.isBefore(change(100L, "bloodReadings", "b"))).isFalse();
    }

    @Test
    void canonicalOrderIsMonotonicWithSameTimestampTiebreak() {
        List<SyncChange> shuffled = new ArrayList<>(List.of(
            change(100L, "medications", "z"),
            change(100L, "bloodReadings", "a"),
            change(100L, "bloodReadings", "b"),
            change(50L, "medications", "a"),
            change(100L, "medications", "a")
        ));
        Collections.shuffle(shuffled);
        shuffled.sort(SyncChange.CANONICAL_ORDER);

        assertThat(shuffled).extracting(c -> c.collection() + "/" + c.id())
            .containsExactly(
                "medications/a",       // ts 50 first
                "bloodReadings/a",     // ts 100, collection bloodReadings < medications
                "bloodReadings/b",
                "medications/a",       // ts 100, collection medications, id a
                "medications/z");      // ts 100, collection medications, id z
    }

    private static SyncChange change(long millis, String collection, String id) {
        return new SyncChange(collection, id, SyncStatus.ACTIVE, Instant.ofEpochMilli(millis), null);
    }
}
