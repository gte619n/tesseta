package com.gte619n.healthfitness.persistence.adhoc;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.adhoc.AdHocSource;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutRepository;
import com.gte619n.healthfitness.core.adhoc.EquipmentContext;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.persistence.workoutprogram.FirestoreWorkoutProgramRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed ad-hoc workout templates at
 * {@code users/{userId}/adhocWorkouts/{adhocId}} (IMPL-ADHOC-01). The workout
 * body ({@link WorkoutDay}) is serialized with the shared program helpers so the
 * on-wire shape matches the rest of the workout tree.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreAdHocWorkoutRepository implements AdHocWorkoutRepository {

    private final Firestore firestore;

    public FirestoreAdHocWorkoutRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection("adhocWorkouts");
    }

    @Override
    public Optional<AdHocWorkout> findById(String userId, String adhocId) {
        DocumentSnapshot snap = await(collection(userId).document(adhocId).get());
        return snap.exists() && !isArchived(snap)
            ? Optional.of(toWorkout(userId, snap)) : Optional.empty();
    }

    @Override
    public List<AdHocWorkout> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId).limit(500).get()).getDocuments();
        return docs.stream().filter(d -> !isArchived(d)).map(d -> toWorkout(userId, d)).toList();
    }

    @Override
    public List<AdHocWorkout> findByUserIncludingArchived(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId).limit(500).get()).getDocuments();
        return docs.stream().map(d -> toWorkout(userId, d)).toList();
    }

    @Override
    public void save(AdHocWorkout w) {
        DocumentReference ref = collection(w.userId()).document(w.adhocId());
        boolean isNew = !await(ref.get()).exists();
        await(ref.set(toBody(w, isNew), SetOptions.merge()));
    }

    @Override
    public void archive(String userId, String adhocId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId).document(adhocId).set(updates, SetOptions.merge()));
    }

    @Override
    public void restore(String userId, String adhocId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId).document(adhocId).set(updates, SetOptions.merge()));
    }

    // ---- serialization ----

    private static Map<String, Object> toBody(AdHocWorkout w, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", w.title());
        body.put("summary", w.summary());
        body.put("source", w.source() == null ? AdHocSource.MANUAL.name() : w.source().name());
        body.put("prompt", w.prompt());
        body.put("equipmentContext", equipmentToWire(w.equipmentContext()));
        body.put("targetDurationMinutes", w.targetDurationMinutes());
        body.put("estimatedDurationSeconds", w.estimatedDurationSeconds());
        body.put("tags", w.tags() == null ? List.of() : w.tags());
        body.put("pinned", w.pinned());
        List<Map<String, Object>> days = FirestoreWorkoutProgramRepository.daysToWire(
            w.day() == null ? List.of() : List.of(w.day()));
        body.put("day", days.isEmpty() ? null : days.get(0));
        body.put("runCount", w.runCount());
        body.put("lastPerformedAt", w.lastPerformedAt() == null ? null : w.lastPerformedAt().toString());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static Object equipmentToWire(EquipmentContext c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("presetId", c.presetId());
        m.put("label", c.label());
        m.put("equipmentIds", c.equipmentIds() == null ? List.of() : c.equipmentIds());
        m.put("freeText", c.freeText());
        return m;
    }

    private AdHocWorkout toWorkout(String userId, DocumentSnapshot s) {
        Object dayRaw = s.get("day");
        WorkoutDay day = null;
        if (dayRaw != null) {
            List<WorkoutDay> days = FirestoreWorkoutProgramRepository.daysFromWire(List.of(dayRaw));
            day = days.isEmpty() ? null : days.get(0);
        }
        Long targetMin = s.getLong("targetDurationMinutes");
        Long estSec = s.getLong("estimatedDurationSeconds");
        Long runCount = s.getLong("runCount");
        String lastPerformed = s.getString("lastPerformedAt");
        return new AdHocWorkout(
            userId,
            s.getId(),
            s.getString("title"),
            s.getString("summary"),
            enumOr(s.getString("source"), AdHocSource.class, AdHocSource.MANUAL),
            s.getString("prompt"),
            equipmentFromWire(s.get("equipmentContext")),
            targetMin == null ? null : targetMin.intValue(),
            estSec == null ? null : estSec.intValue(),
            asStringList(s.get("tags")),
            Boolean.TRUE.equals(s.getBoolean("pinned")),
            day,
            runCount == null ? 0 : runCount.intValue(),
            lastPerformed == null ? null : java.time.Instant.parse(lastPerformed),
            toInstant(s.get("createdAt")),
            toInstant(s.get("updatedAt"))
        );
    }

    @SuppressWarnings("unchecked")
    private static EquipmentContext equipmentFromWire(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Object> cm = (Map<String, Object>) m;
        return new EquipmentContext(
            str(cm.get("presetId")),
            str(cm.get("label")),
            asStringList(cm.get("equipmentIds")),
            str(cm.get("freeText")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (Object v : list) {
            if (v != null) {
                out.add(String.valueOf(v));
            }
        }
        return out;
    }

    private static <E extends Enum<E>> E enumOr(String name, Class<E> type, E def) {
        if (name == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
