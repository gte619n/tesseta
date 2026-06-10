package com.gte619n.healthfitness.persistence.workout;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import com.gte619n.healthfitness.core.workout.Workout;
import com.gte619n.healthfitness.core.workout.WorkoutRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed session-level workout records at
// users/{userId}/workouts/{workoutId}. The writer is the ADR-0012 completion
// fan-out (workoutId = "{programId}_{scheduledId}", so re-PUTs upsert).
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreWorkoutRepository implements WorkoutRepository {

    private static final String SUBCOLLECTION = "workouts";

    private final Firestore firestore;

    public FirestoreWorkoutRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Workout> findById(String userId, String workoutId) {
        DocumentSnapshot snapshot = await(collection(userId).document(workoutId).get());
        if (!snapshot.exists() || isArchived(snapshot)) return Optional.empty();
        return Optional.of(toWorkout(userId, snapshot));
    }

    @Override
    public List<Workout> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId).get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toWorkout(userId, d))
            .toList();
    }

    @Override
    public void save(Workout workout) {
        DocumentReference docRef = collection(workout.userId()).document(workout.workoutId());
        DocumentSnapshot existing = await(docRef.get());
        await(docRef.set(toBody(workout, !existing.exists()), SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String workoutId) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2: archive + bump updatedAt
        // so offline clients learn of the deletion via the delta feed.
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId).document(workoutId).set(updates, SetOptions.merge()));
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(Workout w, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("activityType", w.activityType());
        body.put("locationId", w.locationId());
        body.put("startTime", toTimestamp(w.startTime()));
        body.put("endTime", toTimestamp(w.endTime()));
        body.put("source", w.source());
        // An upsert revives a previously tombstoned doc (un-skip → re-complete).
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static Workout toWorkout(String userId, DocumentSnapshot snapshot) {
        return new Workout(
            userId,
            snapshot.getId(),
            snapshot.getString("activityType"),
            snapshot.getString("locationId"),
            toInstant(snapshot.get("startTime")),
            toInstant(snapshot.get("endTime")),
            snapshot.getString("source"),
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.of(java.util.Date.from(instant));
    }
}
