package com.gte619n.healthfitness.persistence;

import com.gte619n.healthfitness.core.sync.SyncStatus;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import java.time.Instant;
import java.time.LocalDate;

public final class FirestoreMapper {

    /**
     * Firestore key holding the universal sync lifecycle status
     * (IMPL-AND-20, D2/D13). Deliberately distinct from the domain
     * {@code status} key already used by MedicationStatus/GoalStatus.
     */
    public static final String SYNC_STATUS_KEY = "syncStatus";

    private FirestoreMapper() {}

    public static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp ts) return ts.toDate().toInstant();
        throw new IllegalArgumentException(
            "Expected Firestore Timestamp, got " + value.getClass().getName());
    }

    public static Object serverTimestamp() {
        return FieldValue.serverTimestamp();
    }

    /**
     * The sync status of a snapshot, defaulting to {@link SyncStatus#ACTIVE}
     * when the field is missing or unrecognized (D13 lazy default — legacy
     * docs predate the field).
     */
    public static SyncStatus statusOf(DocumentSnapshot snapshot) {
        if (snapshot == null) return SyncStatus.ACTIVE;
        String raw = snapshot.getString(SYNC_STATUS_KEY);
        if (raw == null) return SyncStatus.ACTIVE;
        try {
            return SyncStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return SyncStatus.ACTIVE;
        }
    }

    /**
     * True when the snapshot is an explicit {@link SyncStatus#ARCHIVED}
     * tombstone. Use to exclude soft-deleted rows from list/find paths.
     */
    public static boolean isArchived(DocumentSnapshot snapshot) {
        return statusOf(snapshot) == SyncStatus.ARCHIVED;
    }

    public static String toDocumentId(LocalDate date) {
        return date.toString();
    }

    public static LocalDate fromDocumentId(String documentId) {
        return LocalDate.parse(documentId);
    }
}
