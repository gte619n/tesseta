package com.gte619n.healthfitness.persistence.progression;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.progression.ExerciseLoadingProfile;
import com.gte619n.healthfitness.core.progression.ExerciseLoadingProfileRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Per-(user, exercise) equipment override store at {@code users/{userId}/exerciseLoadingProfiles/{exerciseId}}. */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreExerciseLoadingProfileRepository implements ExerciseLoadingProfileRepository {

    private final Firestore firestore;

    public FirestoreExerciseLoadingProfileRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection("exerciseLoadingProfiles");
    }

    @Override
    public Optional<ExerciseLoadingProfile> find(String userId, String exerciseId) {
        DocumentSnapshot snap = await(collection(userId).document(exerciseId).get());
        return snap.exists() ? Optional.of(toProfile(userId, snap)) : Optional.empty();
    }

    @Override
    public void save(ExerciseLoadingProfile profile) {
        await(collection(profile.userId()).document(profile.exerciseId())
            .set(toBody(profile), SetOptions.merge()));
    }

    private static Map<String, Object> toBody(ExerciseLoadingProfile p) {
        Map<String, Object> body = new HashMap<>();
        body.put("exerciseId", p.exerciseId());
        body.put("loadIncrementLbs", p.loadIncrementLbs());
        body.put("loadOffsetLbs", p.loadOffsetLbs());
        body.put("progressionEligible", p.progressionEligible());
        return body;
    }

    private static ExerciseLoadingProfile toProfile(String userId, DocumentSnapshot s) {
        Double increment = s.getDouble("loadIncrementLbs");
        Double offset = s.getDouble("loadOffsetLbs");
        return new ExerciseLoadingProfile(
            userId,
            s.getString("exerciseId"),
            increment == null ? 0.0 : increment,
            offset == null ? 0.0 : offset,
            Boolean.TRUE.equals(s.getBoolean("progressionEligible"))
        );
    }
}
