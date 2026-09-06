package com.gte619n.healthfitness.persistence.progression;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.progression.BlockMode;
import com.gte619n.healthfitness.core.progression.BlockParameters;
import com.gte619n.healthfitness.core.progression.BlockParametersRepository;
import com.gte619n.healthfitness.core.progression.RepBand;
import com.gte619n.healthfitness.core.progression.SuccessCriterion;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Current block parameters per user at {@code users/{userId}/progressionBlock/current} (single doc). */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreBlockParametersRepository implements BlockParametersRepository {

    private static final String DOC_ID = "current";

    private final Firestore firestore;

    public FirestoreBlockParametersRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection("progressionBlock");
    }

    @Override
    public Optional<BlockParameters> find(String userId) {
        DocumentSnapshot snap = await(collection(userId).document(DOC_ID).get());
        return snap.exists() ? Optional.of(toParameters(userId, snap)) : Optional.empty();
    }

    @Override
    public void save(BlockParameters parameters) {
        await(collection(parameters.userId()).document(DOC_ID)
            .set(toBody(parameters), SetOptions.merge()));
    }

    private static Map<String, Object> toBody(BlockParameters p) {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", p.mode() == null ? null : p.mode().name());
        body.put("expectedDriftPerDay", p.expectedDriftPerDay());

        Map<String, Object> repRanges = new HashMap<>();
        if (p.repRangesByPattern() != null) {
            for (Map.Entry<MovementPattern, RepBand> e : p.repRangesByPattern().entrySet()) {
                RepBand band = e.getValue();
                if (band != null) {
                    Map<String, Object> b = new HashMap<>();
                    b.put("min", band.min());
                    b.put("max", band.max());
                    repRanges.put(e.getKey().name(), b);
                }
            }
        }
        body.put("repRangesByPattern", repRanges);

        Map<String, Object> rirCaps = new HashMap<>();
        if (p.rirCapsByMechanic() != null) {
            for (Map.Entry<Mechanic, Double> e : p.rirCapsByMechanic().entrySet()) {
                if (e.getValue() != null) {
                    rirCaps.put(e.getKey().name(), e.getValue());
                }
            }
        }
        body.put("rirCapsByMechanic", rirCaps);

        Map<String, Object> ceiling = new HashMap<>();
        if (p.weeklySetCeiling() != null) {
            for (Map.Entry<MovementPattern, Integer> e : p.weeklySetCeiling().entrySet()) {
                if (e.getValue() != null) {
                    ceiling.put(e.getKey().name(), e.getValue());
                }
            }
        }
        body.put("weeklySetCeiling", ceiling);

        body.put("successCriterion", p.successCriterion() == null ? null : p.successCriterion().name());
        body.put("manualOverride", p.manualOverride());
        body.put("computedAt", p.computedAt() == null ? null : p.computedAt().toString());
        body.put("version", p.version());
        return body;
    }

    @SuppressWarnings("unchecked")
    private static BlockParameters toParameters(String userId, DocumentSnapshot s) {
        String mode = s.getString("mode");
        String successCriterion = s.getString("successCriterion");
        String computedAt = s.getString("computedAt");
        Long version = s.getLong("version");

        Map<MovementPattern, RepBand> repRanges = new EnumMap<>(MovementPattern.class);
        Object rawReps = s.get("repRangesByPattern");
        if (rawReps instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> band) {
                    Number min = (Number) band.get("min");
                    Number max = (Number) band.get("max");
                    if (min != null && max != null) {
                        repRanges.put(MovementPattern.valueOf(e.getKey().toString()),
                            new RepBand(min.intValue(), max.intValue()));
                    }
                }
            }
        }

        Map<Mechanic, Double> rirCaps = new EnumMap<>(Mechanic.class);
        Object rawCaps = s.get("rirCapsByMechanic");
        if (rawCaps instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    rirCaps.put(Mechanic.valueOf(e.getKey().toString()), n.doubleValue());
                }
            }
        }

        Map<MovementPattern, Integer> ceiling = new EnumMap<>(MovementPattern.class);
        Object rawCeiling = s.get("weeklySetCeiling");
        if (rawCeiling instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    ceiling.put(MovementPattern.valueOf(e.getKey().toString()), n.intValue());
                }
            }
        }

        return new BlockParameters(
            userId,
            mode == null ? null : BlockMode.valueOf(mode),
            s.getDouble("expectedDriftPerDay") == null ? 0.0 : s.getDouble("expectedDriftPerDay"),
            repRanges,
            rirCaps,
            ceiling,
            successCriterion == null ? null : SuccessCriterion.valueOf(successCriterion),
            Boolean.TRUE.equals(s.getBoolean("manualOverride")),
            computedAt == null ? null : Instant.parse(computedAt),
            version == null ? 0 : version
        );
    }
}
