package com.gte619n.healthfitness.persistence.exercise;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.DemoFrame;
import com.gte619n.healthfitness.core.exercise.DemoPhase;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.exercise.RepRange;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed exercise catalog. Documents live at the top-level
 * {@code exercises/{exerciseId}} collection (global, like Equipment). Equipment
 * requirements and demo frames are embedded in the document.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreExerciseRepository implements ExerciseRepository {

    private static final String COLLECTION = "exercises";

    private final Firestore firestore;

    public FirestoreExerciseRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Exercise> findById(String exerciseId) {
        DocumentSnapshot snap = await(collection().document(exerciseId).get());
        return snap.exists() ? Optional.of(toExercise(snap)) : Optional.empty();
    }

    @Override
    public List<Exercise> findByIds(Collection<String> exerciseIds) {
        if (exerciseIds == null || exerciseIds.isEmpty()) {
            return List.of();
        }
        List<Exercise> results = new ArrayList<>();
        List<String> idList = new ArrayList<>(exerciseIds);
        for (int i = 0; i < idList.size(); i += 30) {
            List<String> batch = idList.subList(i, Math.min(i + 30, idList.size()));
            List<QueryDocumentSnapshot> docs = await(collection()
                .whereIn("__name__", batch.stream().map(id -> collection().document(id)).toList())
                .get()).getDocuments();
            docs.stream().map(this::toExercise).forEach(results::add);
        }
        return results;
    }

    @Override
    public List<Exercise> findPublished(String search, MovementPattern pattern, BlockType block, String muscle) {
        // Returns PUBLISHED exercises regardless of media status; the
        // media-approval gate (if any) is applied above this layer based on
        // the app.exercises.require-approved-media flag.
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("status", ExerciseStatus.PUBLISHED.name())
            .limit(1000)
            .get()).getDocuments();
        List<Exercise> all = docs.stream()
            .map(this::toExercise)
            .filter(e -> e.aliasOfExerciseId() == null)
            .toList();
        String searchLower = search == null ? null : search.toLowerCase();
        return all.stream()
            .filter(e -> pattern == null || e.movementPattern() == pattern)
            .filter(e -> block == null || (e.suitableBlockTypes() != null && e.suitableBlockTypes().contains(block)))
            .filter(e -> muscle == null || (e.primaryMuscles() != null
                && e.primaryMuscles().stream().anyMatch(m -> m.equalsIgnoreCase(muscle))))
            .filter(e -> searchLower == null
                || (e.nameLower() != null && e.nameLower().contains(searchLower))
                || (e.aliases() != null && e.aliases().stream().anyMatch(a -> a.toLowerCase().contains(searchLower))))
            .toList();
    }

    @Override
    public List<Exercise> findAll() {
        List<QueryDocumentSnapshot> docs = await(collection()
            .orderBy("nameLower", Query.Direction.ASCENDING)
            .limit(2000)
            .get()).getDocuments();
        return docs.stream().map(this::toExercise).toList();
    }

    @Override
    public List<Exercise> findByMediaStatus(ExerciseMediaStatus mediaStatus) {
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("mediaStatus", mediaStatus.name())
            .limit(500)
            .get()).getDocuments();
        return docs.stream()
            .map(this::toExercise)
            .filter(e -> e.aliasOfExerciseId() == null)
            .toList();
    }

    @Override
    public void save(Exercise exercise) {
        DocumentReference ref = collection().document(exercise.exerciseId());
        boolean isNew = !await(ref.get()).exists();
        await(ref.set(toBody(exercise, isNew), SetOptions.merge()));
    }

    @Override
    public void delete(String exerciseId) {
        await(collection().document(exerciseId).delete());
    }

    private CollectionReference collection() {
        return firestore.collection(COLLECTION);
    }

    private static Map<String, Object> toBody(Exercise e, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", e.name());
        body.put("nameLower", e.nameLower());
        body.put("aliases", orEmpty(e.aliases()));
        body.put("movementPattern", e.movementPattern() == null ? null : e.movementPattern().name());
        body.put("primaryMuscles", orEmpty(e.primaryMuscles()));
        body.put("secondaryMuscles", orEmpty(e.secondaryMuscles()));
        body.put("laterality", e.laterality() == null ? null : e.laterality().name());
        body.put("mechanic", e.mechanic() == null ? null : e.mechanic().name());
        body.put("description", e.description());
        body.put("formCues", orEmpty(e.formCues()));
        body.put("requiredEquipment", requirementsToWire(e.requiredEquipment()));
        body.put("suitableBlockTypes", e.suitableBlockTypes() == null ? List.of()
            : e.suitableBlockTypes().stream().map(Enum::name).toList());
        body.put("defaultRepRange", e.defaultRepRange() == null ? null
            : Map.of("min", e.defaultRepRange().min(), "max", e.defaultRepRange().max()));
        body.put("isTimed", e.isTimed());
        body.put("demoFrames", framesToWire(e.demoFrames()));
        body.put("videoUrl", e.videoUrl());
        body.put("demoPromptOverride", e.demoPromptOverride());
        body.put("mediaStatus", e.mediaStatus() == null ? ExerciseMediaStatus.NONE.name() : e.mediaStatus().name());
        body.put("status", e.status() == null ? ExerciseStatus.DRAFT.name() : e.status().name());
        body.put("contributorId", e.contributorId());
        body.put("aliasOfExerciseId", e.aliasOfExerciseId());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static List<Map<String, Object>> requirementsToWire(List<EquipmentRequirement> reqs) {
        if (reqs == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (EquipmentRequirement r : reqs) {
            Map<String, Object> m = new HashMap<>();
            m.put("anyOf", r.anyOf() == null ? List.of() : r.anyOf());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> framesToWire(List<DemoFrame> frames) {
        if (frames == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DemoFrame f : frames) {
            Map<String, Object> m = new HashMap<>();
            m.put("phase", f.phase() == null ? null : f.phase().name());
            m.put("imageUrl", f.imageUrl());
            m.put("imageCandidates", f.imageCandidates() == null ? List.of() : f.imageCandidates());
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Exercise toExercise(DocumentSnapshot s) {
        String movementPattern = s.getString("movementPattern");
        String laterality = s.getString("laterality");
        String mechanic = s.getString("mechanic");
        String mediaStatus = s.getString("mediaStatus");
        String status = s.getString("status");

        List<EquipmentRequirement> reqs = new ArrayList<>();
        Object rawReqs = s.get("requiredEquipment");
        if (rawReqs instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object anyOf = ((Map<String, Object>) m).get("anyOf");
                    reqs.add(new EquipmentRequirement(anyOf instanceof List<?> a
                        ? a.stream().map(String::valueOf).toList() : List.of()));
                }
            }
        }

        List<BlockType> blockTypes = new ArrayList<>();
        Object rawBlocks = s.get("suitableBlockTypes");
        if (rawBlocks instanceof List<?> list) {
            for (Object o : list) {
                try { blockTypes.add(BlockType.valueOf(String.valueOf(o))); } catch (IllegalArgumentException ignore) { }
            }
        }

        RepRange repRange = null;
        Object rawRange = s.get("defaultRepRange");
        if (rawRange instanceof Map<?, ?> m) {
            Map<String, Object> rm = (Map<String, Object>) m;
            repRange = new RepRange(asInt(rm.get("min")), asInt(rm.get("max")));
        }

        List<DemoFrame> frames = new ArrayList<>();
        Object rawFrames = s.get("demoFrames");
        if (rawFrames instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> fm = (Map<String, Object>) m;
                    String phase = (String) fm.get("phase");
                    Object cands = fm.get("imageCandidates");
                    frames.add(new DemoFrame(
                        phase == null ? null : DemoPhase.valueOf(phase),
                        (String) fm.get("imageUrl"),
                        cands instanceof List<?> c ? c.stream().map(String::valueOf).toList() : List.of()
                    ));
                }
            }
        }

        return new Exercise(
            s.getId(),
            s.getString("name"),
            s.getString("nameLower"),
            asStringList(s.get("aliases")),
            movementPattern == null ? MovementPattern.OTHER : MovementPattern.valueOf(movementPattern),
            asStringList(s.get("primaryMuscles")),
            asStringList(s.get("secondaryMuscles")),
            laterality == null ? Laterality.BILATERAL : Laterality.valueOf(laterality),
            mechanic == null ? Mechanic.COMPOUND : Mechanic.valueOf(mechanic),
            s.getString("description"),
            asStringList(s.get("formCues")),
            reqs,
            blockTypes,
            repRange,
            Boolean.TRUE.equals(s.getBoolean("isTimed")),
            frames,
            s.getString("videoUrl"),
            s.getString("demoPromptOverride"),
            mediaStatus == null ? ExerciseMediaStatus.NONE : ExerciseMediaStatus.valueOf(mediaStatus),
            status == null ? ExerciseStatus.DRAFT : ExerciseStatus.valueOf(status),
            s.getString("contributorId"),
            toInstant(s.get("createdAt")),
            toInstant(s.get("updatedAt")),
            s.getString("aliasOfExerciseId")
        );
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        return o instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static Integer asInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static List<?> orEmpty(List<?> list) {
        return list == null ? List.of() : list;
    }

}
