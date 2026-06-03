package com.gte619n.healthfitness.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.sync.SyncStatus;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FirestoreMapperTest {

    @Test
    void toInstantHandlesNull() {
        assertThat(FirestoreMapper.toInstant(null)).isNull();
    }

    @Test
    void toInstantUnwrapsFirestoreTimestamp() {
        Instant now = Instant.parse("2026-05-21T10:15:30Z");
        Timestamp ts = Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), now.getNano());
        assertThat(FirestoreMapper.toInstant(ts)).isEqualTo(now);
    }

    @Test
    void toInstantRejectsUnexpectedTypes() {
        assertThatThrownBy(() -> FirestoreMapper.toInstant("not a timestamp"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Firestore Timestamp");
    }

    @Test
    void toDocumentIdProducesIsoDate() {
        assertThat(FirestoreMapper.toDocumentId(LocalDate.of(2026, 5, 21)))
            .isEqualTo("2026-05-21");
    }

    @Test
    void fromDocumentIdRoundTrips() {
        LocalDate date = LocalDate.of(2026, 5, 21);
        assertThat(FirestoreMapper.fromDocumentId(FirestoreMapper.toDocumentId(date)))
            .isEqualTo(date);
    }

    // ---- sync status (IMPL-AND-20 Phase 0) ----

    @Test
    void statusOfDefaultsToActiveWhenFieldAbsent() {
        // D13 lazy default: legacy docs predate the syncStatus field.
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.getString(FirestoreMapper.SYNC_STATUS_KEY)).thenReturn(null);
        assertThat(FirestoreMapper.statusOf(snap)).isEqualTo(SyncStatus.ACTIVE);
        assertThat(FirestoreMapper.isArchived(snap)).isFalse();
    }

    @Test
    void statusOfReadsActive() {
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.getString(FirestoreMapper.SYNC_STATUS_KEY)).thenReturn("ACTIVE");
        assertThat(FirestoreMapper.statusOf(snap)).isEqualTo(SyncStatus.ACTIVE);
        assertThat(FirestoreMapper.isArchived(snap)).isFalse();
    }

    @Test
    void statusOfReadsArchivedTombstone() {
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.getString(FirestoreMapper.SYNC_STATUS_KEY)).thenReturn("ARCHIVED");
        assertThat(FirestoreMapper.statusOf(snap)).isEqualTo(SyncStatus.ARCHIVED);
        assertThat(FirestoreMapper.isArchived(snap)).isTrue();
    }

    @Test
    void statusOfFallsBackToActiveOnUnknownValue() {
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.getString(FirestoreMapper.SYNC_STATUS_KEY)).thenReturn("GARBAGE");
        assertThat(FirestoreMapper.statusOf(snap)).isEqualTo(SyncStatus.ACTIVE);
    }

    @Test
    void statusOfHandlesNullSnapshot() {
        assertThat(FirestoreMapper.statusOf(null)).isEqualTo(SyncStatus.ACTIVE);
        assertThat(FirestoreMapper.isArchived(null)).isFalse();
    }
}
