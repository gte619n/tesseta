package com.gte619n.healthfitness.persistence.workoutprogram;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettings;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettingsRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed workout settings repository. A singleton preferences document
 * at users/{userId}/settings/workout.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class WorkoutSettingsRepositoryImpl implements WorkoutSettingsRepository {

    private static final String SETTINGS = "settings";
    private static final String DOC_ID = "workout";

    private final Firestore firestore;

    public WorkoutSettingsRepositoryImpl(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<WorkoutSettings> find(String userId) {
        DocumentSnapshot snapshot = await(document(userId).get());
        if (!snapshot.exists()) {
            return Optional.empty();
        }
        return Optional.of(toSettings(userId, snapshot));
    }

    @Override
    public void save(WorkoutSettings settings) {
        DocumentReference docRef = document(settings.userId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = new HashMap<>();
        body.put("weeklyStreakTarget", settings.weeklyStreakTarget());
        body.put("updatedAt", serverTimestamp());
        if (!existing.exists()) {
            body.put("createdAt", serverTimestamp());
        }
        await(docRef.set(body, SetOptions.mergeFields(
            "weeklyStreakTarget", "updatedAt", "createdAt")));
    }

    private DocumentReference document(String userId) {
        return firestore.collection("users").document(userId)
            .collection(SETTINGS).document(DOC_ID);
    }

    private static WorkoutSettings toSettings(String userId, DocumentSnapshot snapshot) {
        Long target = snapshot.getLong("weeklyStreakTarget");
        int resolved = target != null
            ? WorkoutSettings.clampTarget(target.intValue())
            : WorkoutSettings.DEFAULT_TARGET;
        return new WorkoutSettings(userId, resolved, toInstant(snapshot.get("updatedAt")));
    }
}
