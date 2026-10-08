package com.gte619n.healthfitness.persistence.workoutprogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.sync.SyncChange;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.persistence.nutrition.FirestoreFoodCatalogRepository;
import com.gte619n.healthfitness.persistence.sync.FirestoreSyncChangeReader;
import com.gte619n.healthfitness.testsupport.firestore.FirestoreEmulatorExtension;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Regression for the stale-phone-prescription bug (Oct 2026): scheduled docs
 * were registered for delta sync on both ends, but no write path ever stamped
 * {@code updatedAt}, so the reader's {@code updatedAt >= cursor} scan never
 * emitted them — a server-side prescription rewrite (progression writeback,
 * continuation heal) silently never reached an already-mirrored phone. The
 * payload also lacked {@code scheduledId}, which the Android
 * {@code ScheduledWorkoutDto} requires, so even an emitted change would have
 * been undecodable. Every write must therefore stamp BOTH fields.
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class FirestoreScheduledWorkoutRepositorySyncStampTest {

    private static final String USER = "u1";
    private static final String PROGRAM = "p1";
    private static final String SCHEDULED_ID = "2026-10-08_wd1";

    @Test
    void saveStampsUpdatedAtAndScheduledId(Firestore firestore) throws Exception {
        new FirestoreScheduledWorkoutRepository(firestore).save(scheduled());

        DocumentSnapshot doc = rawDoc(firestore);
        assertThat(doc.getString("scheduledId")).isEqualTo(SCHEDULED_ID);
        assertThat(doc.get("updatedAt")).isInstanceOf(Timestamp.class);
    }

    @Test
    void saveSessionsBumpsUpdatedAtAndBackfillsScheduledIdOnLegacyDocs(Firestore firestore)
        throws Exception {
        // A doc minted before the stamp existed: no updatedAt, no scheduledId.
        firestore.collection("users").document(USER)
            .collection("workoutPrograms").document(PROGRAM)
            .collection("scheduled").document(SCHEDULED_ID)
            .set(Map.of("date", "2026-10-08", "status", "PLANNED", "feeling", 4)).get();

        new FirestoreScheduledWorkoutRepository(firestore).saveSessions(List.of(scheduled()));

        DocumentSnapshot doc = rawDoc(firestore);
        assertThat(doc.get("updatedAt")).isInstanceOf(Timestamp.class);
        assertThat(doc.getString("scheduledId")).isEqualTo(SCHEDULED_ID);
        assertThat(doc.get("session")).isNotNull();
        // update() semantics: the rewrite must not clobber unrelated fields.
        assertThat(doc.getLong("feeling")).isEqualTo(4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savedDocIsEmittedByTheDeltaReaderWithDecodablePayload(Firestore firestore)
        throws Exception {
        // The program parent doc exists in prod; the reader enumerates through it.
        firestore.collection("users").document(USER)
            .collection("workoutPrograms").document(PROGRAM)
            .set(Map.of("title", "t")).get();

        new FirestoreScheduledWorkoutRepository(firestore).save(scheduled());

        FirestoreSyncChangeReader reader = new FirestoreSyncChangeReader(
            firestore,
            new FirestoreFoodCatalogRepository(firestore),
            mock(ObjectProvider.class));
        List<SyncChange> changes = reader.readChanges(USER, null, 500, null);

        SyncChange change = changes.stream()
            .filter(c -> "workoutPrograms/scheduled".equals(c.collection()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "scheduled doc not emitted; got " + changes));
        // Composite id must match the Android mirror row id ("programId/scheduledId").
        assertThat(change.id()).isEqualTo(PROGRAM + "/" + SCHEDULED_ID);
        // The raw payload must carry scheduledId — the client DTO's required id.
        Map<String, Object> payload = (Map<String, Object>) change.doc();
        assertThat(payload.get("scheduledId")).isEqualTo(SCHEDULED_ID);
        assertThat(payload.get("status")).isEqualTo("PLANNED");
    }

    private static ScheduledWorkout scheduled() {
        WorkoutDay session = new WorkoutDay("wd1", "Pull", DayOfWeek.THU, "loc1", 0, List.of());
        return new ScheduledWorkout(
            USER, PROGRAM, SCHEDULED_ID, LocalDate.parse("2026-10-08"),
            "ph1", "wd1", "Pull", 1, false, "loc1",
            ScheduledStatus.PLANNED, session, null, null, null);
    }

    private static DocumentSnapshot rawDoc(Firestore firestore) throws Exception {
        return firestore.collection("users").document(USER)
            .collection("workoutPrograms").document(PROGRAM)
            .collection("scheduled").document(SCHEDULED_ID)
            .get().get();
    }
}
