package com.gte619n.healthfitness.persistence.progression;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.progression.WeekParameters;
import com.gte619n.healthfitness.core.progression.WeekParametersRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Current week-loop parameters per user at {@code users/{userId}/progressionWeek/current} (single doc). */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreWeekParametersRepository implements WeekParametersRepository {

    private static final String DOC_ID = "current";

    private final Firestore firestore;

    public FirestoreWeekParametersRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection("progressionWeek");
    }

    @Override
    public Optional<WeekParameters> find(String userId) {
        DocumentSnapshot snap = await(collection(userId).document(DOC_ID).get());
        return snap.exists() ? Optional.of(toParameters(userId, snap)) : Optional.empty();
    }

    @Override
    public void save(WeekParameters parameters) {
        await(collection(parameters.userId()).document(DOC_ID)
            .set(toBody(parameters), SetOptions.merge()));
    }

    private static Map<String, Object> toBody(WeekParameters p) {
        Map<String, Object> body = new HashMap<>();

        Map<String, Object> targets = new HashMap<>();
        if (p.weeklySetTargetByPattern() != null) {
            for (Map.Entry<MovementPattern, Integer> e : p.weeklySetTargetByPattern().entrySet()) {
                if (e.getValue() != null) {
                    targets.put(e.getKey().name(), e.getValue());
                }
            }
        }
        body.put("weeklySetTargetByPattern", targets);

        List<String> deload = new ArrayList<>();
        if (p.deloadActiveForPatterns() != null) {
            for (MovementPattern pattern : p.deloadActiveForPatterns()) {
                deload.add(pattern.name());
            }
        }
        body.put("deloadActiveForPatterns", deload);

        body.put("computedAt", p.computedAt() == null ? null : p.computedAt().toString());
        return body;
    }

    private static WeekParameters toParameters(String userId, DocumentSnapshot s) {
        Map<MovementPattern, Integer> targets = new EnumMap<>(MovementPattern.class);
        Object rawTargets = s.get("weeklySetTargetByPattern");
        if (rawTargets instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    targets.put(MovementPattern.valueOf(e.getKey().toString()), n.intValue());
                }
            }
        }

        Set<MovementPattern> deload = new HashSet<>();
        Object rawDeload = s.get("deloadActiveForPatterns");
        if (rawDeload instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    deload.add(MovementPattern.valueOf(o.toString()));
                }
            }
        }

        String computedAt = s.getString("computedAt");
        return new WeekParameters(
            userId,
            targets,
            deload,
            computedAt == null ? null : Instant.parse(computedAt)
        );
    }
}
