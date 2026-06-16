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
import com.gte619n.healthfitness.core.exercise.ExerciseReference;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.FrameSpec;
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
    public List<Exercise> findByPlanStatus(ExerciseMediaStatus planStatus) {
        // planStatus is absent on legacy docs (treated as NONE); query the
        // stored string and, for NONE, also fold in docs missing the field.
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("planStatus", planStatus.name())
            .limit(500)
            .get()).getDocuments();
        List<Exercise> matched = docs.stream()
            .map(this::toExercise)
            .filter(e -> e.aliasOfExerciseId() == null)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (planStatus == ExerciseMediaStatus.NONE) {
            // Legacy/seeded docs written before IMPL-19 have no planStatus field;
            // toExercise() defaults them to NONE but the equality query misses
            // them. Sweep findAll() for those, de-duplicating by id.
            java.util.Set<String> seen = matched.stream()
                .map(Exercise::exerciseId)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
            for (Exercise e : findAll()) {
                if (e.aliasOfExerciseId() == null
                    && e.planStatus() == ExerciseMediaStatus.NONE
                    && seen.add(e.exerciseId())) {
                    matched.add(e);
                }
            }
        }
        return matched;
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
        body.put("demoPlan", planToWire(e.demoPlan()));
        body.put("planStatus", e.planStatus() == null ? ExerciseMediaStatus.NONE.name() : e.planStatus().name());
        body.put("reference", referenceToWire(e.reference()));
        body.put("videoUrl", e.videoUrl());
        body.put("demoPromptOverride", e.demoPromptOverride());
        body.put("mediaStatus", e.mediaStatus() == null ? ExerciseMediaStatus.NONE.name() : e.mediaStatus().name());
        body.put("status", e.status() == null ? ExerciseStatus.DRAFT.name() : e.status().name());
        body.put("contributorId", e.contributorId());
        body.put("aliasOfExerciseId", e.aliasOfExerciseId());
        // IMPL-20 (additive): human sign-off + persisted grounding URL selection.
        body.put("reviewed", e.reviewed());
        body.put("groundingImageUrls", orEmpty(e.groundingImageUrls()));
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
            m.put("key", f.key());
            m.put("label", f.label());
            m.put("caption", f.caption());
            m.put("order", f.order());
            m.put("imageUrl", f.imageUrl());
            m.put("imageCandidates", f.imageCandidates() == null ? List.of() : f.imageCandidates());
            m.put("phase", f.phase() == null ? null : f.phase().name());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> planToWire(List<FrameSpec> plan) {
        if (plan == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (FrameSpec f : plan) {
            Map<String, Object> m = new HashMap<>();
            m.put("key", f.key());
            m.put("order", f.order());
            m.put("label", f.label());
            m.put("caption", f.caption());
            m.put("positionPrompt", f.positionPrompt());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> referenceToWire(ExerciseReference r) {
        if (r == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("url", r.url());
        m.put("source", r.source());
        m.put("name", r.name());
        m.put("score", r.score());
        m.put("match", r.match());
        m.put("images", r.images() == null ? List.of() : r.images());
        m.put("groundingImages", r.groundingImages());
        return m;
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
                    String phaseStr = (String) fm.get("phase");
                    DemoPhase phase = phaseStr == null ? null : DemoPhase.valueOf(phaseStr);
                    Object cands = fm.get("imageCandidates");
                    String key = (String) fm.get("key");
                    String label = (String) fm.get("label");
                    String caption = (String) fm.get("caption");
                    Integer order = asInt(fm.get("order"));
                    // Legacy read: a frame with a phase but no key (written before
                    // IMPL-19) — derive key from phase, order from phase index,
                    // and synthesize empty label/caption.
                    if (key == null && phase != null) {
                        key = DemoFrame.keyForPhase(phase);
                        if (order == null) order = phaseIndex(phase);
                        if (label == null) label = "";
                        if (caption == null) caption = "";
                    }
                    frames.add(new DemoFrame(
                        key,
                        label,
                        caption,
                        order == null ? 0 : order,
                        (String) fm.get("imageUrl"),
                        cands instanceof List<?> c ? c.stream().map(String::valueOf).toList() : List.of(),
                        phase
                    ));
                }
            }
        }

        List<FrameSpec> demoPlan = null;
        Object rawPlan = s.get("demoPlan");
        if (rawPlan instanceof List<?> list) {
            demoPlan = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> pm = (Map<String, Object>) m;
                    Integer order = asInt(pm.get("order"));
                    demoPlan.add(new FrameSpec(
                        (String) pm.get("key"),
                        order == null ? 0 : order,
                        (String) pm.get("label"),
                        (String) pm.get("caption"),
                        (String) pm.get("positionPrompt")
                    ));
                }
            }
        }

        String planStatus = s.getString("planStatus");

        ExerciseReference reference = null;
        Object rawRef = s.get("reference");
        if (rawRef instanceof Map<?, ?> m) {
            Map<String, Object> rm = (Map<String, Object>) m;
            Object score = rm.get("score");
            Object images = rm.get("images");
            Object grounding = rm.get("groundingImages");
            reference = new ExerciseReference(
                (String) rm.get("url"),
                (String) rm.get("source"),
                (String) rm.get("name"),
                score instanceof Number n ? n.doubleValue() : null,
                (String) rm.get("match"),
                images instanceof List<?> il ? il.stream().map(String::valueOf).toList() : List.of(),
                grounding instanceof List<?> gl ? gl.stream().map(String::valueOf).toList() : null
            );
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
            demoPlan,
            planStatus == null ? ExerciseMediaStatus.NONE : ExerciseMediaStatus.valueOf(planStatus),
            reference,
            status == null ? ExerciseStatus.DRAFT : ExerciseStatus.valueOf(status),
            s.getString("contributorId"),
            toInstant(s.get("createdAt")),
            toInstant(s.get("updatedAt")),
            s.getString("aliasOfExerciseId"),
            // IMPL-20: legacy docs lack these — default false / empty.
            Boolean.TRUE.equals(s.getBoolean("reviewed")),
            asStringList(s.get("groundingImageUrls"))
        );
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        return o instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static Integer asInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static int phaseIndex(DemoPhase phase) {
        return switch (phase) {
            case START -> 0;
            case MID -> 1;
            case END -> 2;
        };
    }

    private static List<?> orEmpty(List<?> list) {
        return list == null ? List.of() : list;
    }

}
