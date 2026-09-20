package com.gte619n.healthfitness.persistence.adhoc;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.adhoc.AdHocSessionRepository;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.persistence.workoutprogram.FirestoreWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed ad-hoc run sessions at
 * {@code users/{userId}/adhocWorkouts/{adhocId}/sessions/{sessionId}} (AD-04).
 * Runs are stored as {@link ScheduledWorkout} bodies ({@code programId} carries
 * the adhocId, {@code scheduledId} the sessionId).
 *
 * <p>{@link #findCompletedByUser} enumerates the user's own template docs and
 * reads each one's {@code sessions} subcollection — the same strict per-user
 * walk the delta reader uses (ADR-0021), avoiding a cross-user
 * {@code collectionGroup} scan. Bounded by template count, not account age.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreAdHocSessionRepository implements AdHocSessionRepository {

    private final Firestore firestore;

    public FirestoreAdHocSessionRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference sessions(String userId, String adhocId) {
        return firestore.collection("users").document(userId)
            .collection("adhocWorkouts").document(adhocId)
            .collection("sessions");
    }

    @Override
    public Optional<ScheduledWorkout> findById(String userId, String adhocId, String sessionId) {
        DocumentSnapshot snap = await(sessions(userId, adhocId).document(sessionId).get());
        return snap.exists() ? Optional.of(toSession(userId, adhocId, snap)) : Optional.empty();
    }

    @Override
    public List<ScheduledWorkout> findByWorkout(String userId, String adhocId) {
        List<QueryDocumentSnapshot> docs = await(sessions(userId, adhocId)
            .orderBy("date", Query.Direction.DESCENDING).get()).getDocuments();
        return docs.stream().map(d -> toSession(userId, adhocId, d)).toList();
    }

    @Override
    public int countByWorkout(String userId, String adhocId) {
        return (int) await(sessions(userId, adhocId).count().get()).getCount();
    }

    @Override
    public List<ScheduledWorkout> findCompletedByUser(String userId, LocalDate from, LocalDate to) {
        List<ScheduledWorkout> out = new ArrayList<>();
        String fromStr = from == null ? null : from.toString();
        String toStr = to == null ? null : to.toString();
        for (DocumentReference template :
            firestore.collection("users").document(userId).collection("adhocWorkouts").listDocuments()) {
            Query q = template.collection("sessions")
                .whereEqualTo("status", ScheduledStatus.COMPLETED.name());
            for (QueryDocumentSnapshot d : await(q.get()).getDocuments()) {
                String dateStr = d.getString("date");
                if (dateStr == null) {
                    continue;
                }
                if (fromStr != null && dateStr.compareTo(fromStr) < 0) {
                    continue;
                }
                if (toStr != null && dateStr.compareTo(toStr) > 0) {
                    continue;
                }
                out.add(toSession(userId, template.getId(), d));
            }
        }
        out.sort(Comparator.comparing(
            ScheduledWorkout::date, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    @Override
    public void save(String adhocId, ScheduledWorkout s) {
        await(sessions(s.userId(), adhocId).document(s.scheduledId())
            .set(toBody(s), SetOptions.merge()));
    }

    // ---- serialization (mirrors FirestoreScheduledWorkoutRepository) ----

    private static Map<String, Object> toBody(ScheduledWorkout sw) {
        Map<String, Object> body = new HashMap<>();
        // userId persisted so a future collection-group read could filter by owner.
        body.put("userId", sw.userId());
        body.put("date", sw.date() == null ? null : sw.date().toString());
        body.put("phaseId", sw.phaseId());
        body.put("dayId", sw.dayId());
        body.put("dayLabel", sw.dayLabel());
        body.put("weekIndexInPhase", sw.weekIndexInPhase());
        body.put("isDeload", sw.isDeload());
        body.put("locationId", sw.locationId());
        body.put("status", sw.status() == null ? ScheduledStatus.PLANNED.name() : sw.status().name());
        body.put("completedAt", sw.completedAt() == null ? null : sw.completedAt().toString());
        body.put("durationSeconds", sw.durationSeconds());
        body.put("feeling", sw.feeling());
        List<Map<String, Object>> days = FirestoreWorkoutProgramRepository.daysToWire(
            sw.session() == null ? List.of() : List.of(sw.session()));
        body.put("session", days.isEmpty() ? null : days.get(0));
        return body;
    }

    private ScheduledWorkout toSession(String userId, String adhocId, DocumentSnapshot s) {
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
        Long feeling = s.getLong("feeling");
        return new ScheduledWorkout(
            userId, adhocId, s.getId(),
            dateStr == null ? null : LocalDate.parse(dateStr),
            s.getString("phaseId"), s.getString("dayId"), s.getString("dayLabel"),
            week == null ? 1 : week.intValue(),
            Boolean.TRUE.equals(s.getBoolean("isDeload")),
            s.getString("locationId"),
            statusStr == null ? ScheduledStatus.PLANNED : ScheduledStatus.valueOf(statusStr),
            day,
            completedAtStr == null ? null : Instant.parse(completedAtStr),
            duration == null ? null : duration.intValue(),
            feeling == null ? null : feeling.intValue()
        );
    }
}
