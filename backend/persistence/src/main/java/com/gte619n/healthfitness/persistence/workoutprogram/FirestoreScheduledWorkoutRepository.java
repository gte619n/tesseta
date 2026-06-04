package com.gte619n.healthfitness.persistence.workoutprogram;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Materialized scheduled workouts at
 * {@code users/{userId}/workoutPrograms/{programId}/scheduled/{scheduledId}}.
 * Dates are stored as ISO strings so lexicographic range queries work.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreScheduledWorkoutRepository implements ScheduledWorkoutRepository {

    /** Firestore commits at most 500 writes per batch. */
    private static final int MAX_BATCH = 500;

    private final Firestore firestore;

    public FirestoreScheduledWorkoutRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId, String programId) {
        return firestore.collection("users").document(userId)
            .collection("workoutPrograms").document(programId)
            .collection("scheduled");
    }

    @Override
    public List<ScheduledWorkout> findByProgram(String userId, String programId, LocalDate from, LocalDate to) {
        List<QueryDocumentSnapshot> docs = await(collection(userId, programId)
            .whereGreaterThanOrEqualTo("date", from.toString())
            .whereLessThanOrEqualTo("date", to.toString())
            .orderBy("date", Query.Direction.ASCENDING)
            .get()).getDocuments();
        return docs.stream().map(d -> toScheduled(userId, programId, d)).toList();
    }

    @Override
    public void save(ScheduledWorkout sw) {
        await(collection(sw.userId(), sw.programId()).document(sw.scheduledId())
            .set(toBody(sw), SetOptions.merge()));
    }

    @Override
    public void saveAll(List<ScheduledWorkout> items) {
        if (items.isEmpty()) {
            return;
        }
        // One batched commit per 500 sessions instead of a write per session.
        for (int start = 0; start < items.size(); start += MAX_BATCH) {
            WriteBatch batch = firestore.batch();
            for (ScheduledWorkout sw : items.subList(start, Math.min(start + MAX_BATCH, items.size()))) {
                batch.set(collection(sw.userId(), sw.programId()).document(sw.scheduledId()),
                    toBody(sw), SetOptions.merge());
            }
            await(batch.commit());
        }
    }

    private static Map<String, Object> toBody(ScheduledWorkout sw) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", sw.date().toString());
        body.put("phaseId", sw.phaseId());
        body.put("dayId", sw.dayId());
        body.put("dayLabel", sw.dayLabel());
        body.put("weekIndexInPhase", sw.weekIndexInPhase());
        body.put("isDeload", sw.isDeload());
        body.put("locationId", sw.locationId());
        body.put("status", sw.status() == null ? ScheduledStatus.PLANNED.name() : sw.status().name());
        body.put("completedAt", sw.completedAt() == null ? null : sw.completedAt().toString());
        body.put("durationSeconds", sw.durationSeconds());
        List<Map<String, Object>> sessionDays =
            FirestoreWorkoutProgramRepository.daysToWire(sw.session() == null ? List.of() : List.of(sw.session()));
        body.put("session", sessionDays.isEmpty() ? null : sessionDays.get(0));
        return body;
    }

    @Override
    public int countByStatus(String userId, String programId, ScheduledStatus status) {
        // Count aggregation: server-side tally, reads no documents.
        return (int) await(collection(userId, programId)
            .whereEqualTo("status", status.name())
            .count().get()).getCount();
    }

    @Override
    public Optional<LocalDate> latestDateByStatus(String userId, String programId, ScheduledStatus status) {
        // Reverse of the (status, date) composite index — one doc, not the whole set.
        List<QueryDocumentSnapshot> docs = await(collection(userId, programId)
            .whereEqualTo("status", status.name())
            .orderBy("date", Query.Direction.DESCENDING)
            .limit(1)
            .get()).getDocuments();
        if (docs.isEmpty()) {
            return Optional.empty();
        }
        String date = docs.get(0).getString("date");
        return Optional.ofNullable(date == null ? null : LocalDate.parse(date));
    }

    @Override
    public void deletePlannedFrom(String userId, String programId, LocalDate from) {
        List<QueryDocumentSnapshot> docs = await(collection(userId, programId)
            .whereGreaterThanOrEqualTo("date", from.toString())
            .whereEqualTo("status", ScheduledStatus.PLANNED.name())
            .get()).getDocuments();
        // Batched deletes (≤500/commit) rather than a round-trip per doc.
        for (int start = 0; start < docs.size(); start += MAX_BATCH) {
            WriteBatch batch = firestore.batch();
            for (QueryDocumentSnapshot d : docs.subList(start, Math.min(start + MAX_BATCH, docs.size()))) {
                batch.delete(d.getReference());
            }
            await(batch.commit());
        }
    }

    private ScheduledWorkout toScheduled(String userId, String programId, DocumentSnapshot s) {
        String dateStr = s.getString("date");
        Object session = s.get("session");
        WorkoutDay day = null;
        if (session != null) {
            List<WorkoutDay> days = FirestoreWorkoutProgramRepository.daysFromWire(List.of(session));
            day = days.isEmpty() ? null : days.get(0);
        }
        String statusStr = s.getString("status");
        Long week = s.getLong("weekIndexInPhase");
        String completedAtStr = s.getString("completedAt");
        Long duration = s.getLong("durationSeconds");
        return new ScheduledWorkout(
            userId, programId, s.getId(),
            dateStr == null ? null : LocalDate.parse(dateStr),
            s.getString("phaseId"), s.getString("dayId"), s.getString("dayLabel"),
            week == null ? 1 : week.intValue(),
            Boolean.TRUE.equals(s.getBoolean("isDeload")),
            s.getString("locationId"),
            statusStr == null ? ScheduledStatus.PLANNED : ScheduledStatus.valueOf(statusStr),
            day,
            completedAtStr == null ? null : Instant.parse(completedAtStr),
            duration == null ? null : duration.intValue()
        );
    }

}
