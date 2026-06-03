package com.gte619n.healthfitness.persistence.workoutprogram;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.DeloadModifier;
import com.gte619n.healthfitness.core.workoutprogram.Intensity;
import com.gte619n.healthfitness.core.workoutprogram.IntensityKind;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed workout programs at
 * {@code users/{userId}/workoutPrograms/{programId}}. The full phase/day/block/
 * prescription tree is embedded in the document (bounded weekly template).
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreWorkoutProgramRepository implements WorkoutProgramRepository {

    private final Firestore firestore;

    public FirestoreWorkoutProgramRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection("workoutPrograms");
    }

    @Override
    public Optional<WorkoutProgram> findById(String userId, String programId) {
        DocumentSnapshot snap = await(collection(userId).document(programId).get());
        return snap.exists() && !isArchived(snap)
            ? Optional.of(toProgram(userId, snap)) : Optional.empty();
    }

    @Override
    public List<WorkoutProgram> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId).limit(200).get()).getDocuments();
        return docs.stream().filter(d -> !isArchived(d)).map(d -> toProgram(userId, d)).toList();
    }

    @Override
    public void save(WorkoutProgram program) {
        DocumentReference ref = collection(program.userId()).document(program.programId());
        boolean isNew = !await(ref.get()).exists();
        await(ref.set(toBody(program, isNew), SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String programId) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2: archive + bump updatedAt so
        // offline clients learn of the deletion via the delta feed.
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId).document(programId).set(updates, SetOptions.merge()));
    }

    // ---- serialization ----

    private static Map<String, Object> toBody(WorkoutProgram p, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", p.title());
        body.put("description", p.description());
        body.put("goalId", p.goalId());
        body.put("status", p.status() == null ? ProgramStatus.DRAFT.name() : p.status().name());
        body.put("source", p.source() == null ? ProgramSource.MANUAL.name() : p.source().name());
        body.put("startDate", p.startDate() == null ? null : p.startDate().toString());
        body.put("schedule", scheduleToWire(p.schedule()));
        body.put("phaseOrder", p.phaseOrder() == null ? List.of() : p.phaseOrder());
        body.put("phases", phasesToWire(p.phases()));
        body.put("completedAt", p.completedAt() == null ? null : p.completedAt().toString());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static Object scheduleToWire(ProgramSchedule s) {
        if (s == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("trainingDays", s.trainingDays() == null ? List.of()
            : s.trainingDays().stream().map(Enum::name).toList());
        Map<String, Object> locs = new HashMap<>();
        if (s.dayLocations() != null) {
            s.dayLocations().forEach((k, v) -> locs.put(k.name(), v));
        }
        m.put("dayLocations", locs);
        return m;
    }

    private static List<Map<String, Object>> phasesToWire(List<ProgramPhase> phases) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (phases == null) return out;
        for (ProgramPhase p : phases) {
            Map<String, Object> m = new HashMap<>();
            m.put("phaseId", p.phaseId());
            m.put("title", p.title());
            m.put("focus", p.focus());
            m.put("orderIndex", p.orderIndex());
            m.put("status", p.status() == null ? ProgramPhaseStatus.LOCKED.name() : p.status().name());
            m.put("weeks", p.weeks());
            m.put("deloadWeekIndex", p.deloadWeekIndex());
            m.put("targetStartDate", p.targetStartDate() == null ? null : p.targetStartDate().toString());
            m.put("targetEndDate", p.targetEndDate() == null ? null : p.targetEndDate().toString());
            m.put("completedAt", p.completedAt() == null ? null : p.completedAt().toString());
            m.put("days", daysToWire(p.days()));
            out.add(m);
        }
        return out;
    }

    static List<Map<String, Object>> daysToWire(List<WorkoutDay> days) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (days == null) return out;
        for (WorkoutDay d : days) {
            Map<String, Object> m = new HashMap<>();
            m.put("dayId", d.dayId());
            m.put("label", d.label());
            m.put("dayOfWeek", d.dayOfWeek() == null ? null : d.dayOfWeek().name());
            m.put("locationId", d.locationId());
            m.put("orderIndex", d.orderIndex());
            m.put("blocks", blocksToWire(d.blocks()));
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> blocksToWire(List<Block> blocks) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (blocks == null) return out;
        for (Block b : blocks) {
            Map<String, Object> m = new HashMap<>();
            m.put("blockId", b.blockId());
            m.put("type", b.type() == null ? null : b.type().name());
            m.put("title", b.title());
            m.put("orderIndex", b.orderIndex());
            List<Map<String, Object>> rxs = new ArrayList<>();
            if (b.prescriptions() != null) {
                for (Prescription rx : b.prescriptions()) {
                    Map<String, Object> rm = new HashMap<>();
                    rm.put("exerciseId", rx.exerciseId());
                    rm.put("orderIndex", rx.orderIndex());
                    rm.put("sets", rx.sets());
                    rm.put("repsMin", rx.repsMin());
                    rm.put("repsMax", rx.repsMax());
                    rm.put("durationSeconds", rx.durationSeconds());
                    if (rx.intensity() != null) {
                        Map<String, Object> im = new HashMap<>();
                        im.put("kind", rx.intensity().kind() == null ? null : rx.intensity().kind().name());
                        im.put("value", rx.intensity().value());
                        rm.put("intensity", im);
                    }
                    rm.put("restSeconds", rx.restSeconds());
                    rm.put("tempo", rx.tempo());
                    rm.put("notes", rx.notes());
                    if (rx.deloadModifier() != null) {
                        Map<String, Object> dm = new HashMap<>();
                        dm.put("setsMultiplier", rx.deloadModifier().setsMultiplier());
                        dm.put("intensityDelta", rx.deloadModifier().intensityDelta());
                        rm.put("deloadModifier", dm);
                    }
                    if (rx.loggedSets() != null && !rx.loggedSets().isEmpty()) {
                        List<Map<String, Object>> ls = new ArrayList<>();
                        for (LoggedSet s : rx.loggedSets()) {
                            Map<String, Object> sm = new HashMap<>();
                            sm.put("weightLbs", s.weightLbs());
                            sm.put("reps", s.reps());
                            ls.add(sm);
                        }
                        rm.put("loggedSets", ls);
                    }
                    rxs.add(rm);
                }
            }
            m.put("prescriptions", rxs);
            out.add(m);
        }
        return out;
    }

    // ---- deserialization ----

    private WorkoutProgram toProgram(String userId, DocumentSnapshot s) {
        return new WorkoutProgram(
            userId,
            s.getId(),
            s.getString("title"),
            s.getString("description"),
            s.getString("goalId"),
            enumOr(s.getString("status"), ProgramStatus.class, ProgramStatus.DRAFT),
            enumOr(s.getString("source"), ProgramSource.class, ProgramSource.MANUAL),
            parseDate(s.getString("startDate")),
            scheduleFromWire(s.get("schedule")),
            asStringList(s.get("phaseOrder")),
            phasesFromWire(s.get("phases")),
            toInstant(s.get("createdAt")),
            toInstant(s.get("updatedAt")),
            parseInstant(s.getString("completedAt"))
        );
    }

    @SuppressWarnings("unchecked")
    private static ProgramSchedule scheduleFromWire(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> sm = (Map<String, Object>) m;
        List<DayOfWeek> trainingDays = new ArrayList<>();
        if (sm.get("trainingDays") instanceof List<?> list) {
            for (Object o : list) {
                try { trainingDays.add(DayOfWeek.valueOf(String.valueOf(o))); } catch (IllegalArgumentException ignore) { }
            }
        }
        Map<DayOfWeek, String> locs = new EnumMap<>(DayOfWeek.class);
        if (sm.get("dayLocations") instanceof Map<?, ?> dl) {
            ((Map<String, Object>) dl).forEach((k, v) -> {
                try { locs.put(DayOfWeek.valueOf(k), String.valueOf(v)); } catch (IllegalArgumentException ignore) { }
            });
        }
        return new ProgramSchedule(trainingDays, locs);
    }

    @SuppressWarnings("unchecked")
    private static List<ProgramPhase> phasesFromWire(Object raw) {
        List<ProgramPhase> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> pm = (Map<String, Object>) m;
            out.add(new ProgramPhase(
                str(pm.get("phaseId")),
                str(pm.get("title")),
                str(pm.get("focus")),
                asInt(pm.get("orderIndex"), 0),
                enumOr(str(pm.get("status")), ProgramPhaseStatus.class, ProgramPhaseStatus.LOCKED),
                asInt(pm.get("weeks"), 1),
                pm.get("deloadWeekIndex") instanceof Number n ? n.intValue() : null,
                parseDate(str(pm.get("targetStartDate"))),
                parseDate(str(pm.get("targetEndDate"))),
                parseInstant(str(pm.get("completedAt"))),
                daysFromWire(pm.get("days"))
            ));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<WorkoutDay> daysFromWire(Object raw) {
        List<WorkoutDay> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> dm = (Map<String, Object>) m;
            out.add(new WorkoutDay(
                str(dm.get("dayId")),
                str(dm.get("label")),
                enumOrNull(str(dm.get("dayOfWeek")), DayOfWeek.class),
                str(dm.get("locationId")),
                asInt(dm.get("orderIndex"), 0),
                blocksFromWire(dm.get("blocks"))
            ));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Block> blocksFromWire(Object raw) {
        List<Block> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> bm = (Map<String, Object>) m;
            out.add(new Block(
                str(bm.get("blockId")),
                enumOr(str(bm.get("type")), BlockType.class, BlockType.MAIN),
                str(bm.get("title")),
                asInt(bm.get("orderIndex"), 0),
                prescriptionsFromWire(bm.get("prescriptions"))
            ));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Prescription> prescriptionsFromWire(Object raw) {
        List<Prescription> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> rm = (Map<String, Object>) m;
            Intensity intensity = null;
            if (rm.get("intensity") instanceof Map<?, ?> im) {
                Map<String, Object> imm = (Map<String, Object>) im;
                intensity = new Intensity(
                    enumOrNull(str(imm.get("kind")), IntensityKind.class),
                    imm.get("value") instanceof Number n ? n.doubleValue() : null);
            }
            DeloadModifier deload = null;
            if (rm.get("deloadModifier") instanceof Map<?, ?> dm) {
                Map<String, Object> dmm = (Map<String, Object>) dm;
                deload = new DeloadModifier(
                    dmm.get("setsMultiplier") instanceof Number a ? a.doubleValue() : null,
                    dmm.get("intensityDelta") instanceof Number b ? b.doubleValue() : null);
            }
            out.add(new Prescription(
                str(rm.get("exerciseId")),
                asInt(rm.get("orderIndex"), 0),
                intOrNull(rm.get("sets")),
                intOrNull(rm.get("repsMin")),
                intOrNull(rm.get("repsMax")),
                intOrNull(rm.get("durationSeconds")),
                intensity,
                intOrNull(rm.get("restSeconds")),
                str(rm.get("tempo")),
                str(rm.get("notes")),
                deload,
                loggedSetsFromWire(rm.get("loggedSets"))
            ));
        }
        return out;
    }

    private static List<LoggedSet> loggedSetsFromWire(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<LoggedSet> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object w = m.get("weightLbs");
            out.add(new LoggedSet(
                w instanceof Number n ? n.doubleValue() : null,
                intOrNull(m.get("reps"))));
        }
        return out;
    }

    // ---- helpers ----

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Integer intOrNull(Object o) { return o instanceof Number n ? n.intValue() : null; }
    private static int asInt(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static LocalDate parseDate(String s) { return s == null ? null : LocalDate.parse(s); }
    private static Instant parseInstant(String s) { return s == null ? null : Instant.parse(s); }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        return o instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static <E extends Enum<E>> E enumOr(String name, Class<E> type, E def) {
        if (name == null) return def;
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException e) { return def; }
    }

    private static <E extends Enum<E>> E enumOrNull(String name, Class<E> type) {
        if (name == null) return null;
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException e) { return null; }
    }

    private static <T> T await(ApiFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Firestore call interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Firestore call failed", e.getCause());
        }
    }
}
