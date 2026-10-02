package com.gte619n.healthfitness.persistence.catalog;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.catalog.CatalogProvenance;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.catalog.CatalogWorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.catalog.CatalogWorkoutProgramRepository;
import com.gte619n.healthfitness.persistence.workoutprogram.FirestoreWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed shared program templates at top-level
 * {@code programCatalog/{catalogId}} (IMPL-MULTIUSER-01 P3.2 / D9).
 *
 * <p>Reuses {@link FirestoreWorkoutProgramRepository}'s public day/block/
 * prescription wire helpers so the embedded workout tree serializes identically
 * to a per-user program; this repo adds only the catalog-specific framing
 * (status/provenance, stripped per-user fields, phase framing).
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreCatalogWorkoutProgramRepository implements CatalogWorkoutProgramRepository {

    private final Firestore firestore;

    public FirestoreCatalogWorkoutProgramRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection() {
        return firestore.collection("programCatalog");
    }

    @Override
    public Optional<CatalogWorkoutProgram> findById(String catalogId) {
        DocumentSnapshot snap = await(collection().document(catalogId).get());
        return snap.exists() ? Optional.of(toCatalog(snap)) : Optional.empty();
    }

    @Override
    public List<CatalogWorkoutProgram> findByStatus(CatalogStatus status) {
        List<QueryDocumentSnapshot> docs =
            await(collection().whereEqualTo("status", status.name()).limit(500).get()).getDocuments();
        return docs.stream().map(this::toCatalog).toList();
    }

    @Override
    public List<CatalogWorkoutProgram> findAll() {
        List<QueryDocumentSnapshot> docs = await(collection().limit(500).get()).getDocuments();
        return docs.stream().map(this::toCatalog).toList();
    }

    @Override
    public void save(CatalogWorkoutProgram p) {
        DocumentReference ref = collection().document(p.catalogId());
        boolean isNew = !await(ref.get()).exists();
        await(ref.set(toBody(p, isNew), SetOptions.merge()));
    }

    // ---- serialization ----

    private static Map<String, Object> toBody(CatalogWorkoutProgram p, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", p.title());
        body.put("description", p.description());
        body.put("schedule", scheduleToWire(p.schedule()));
        body.put("phaseOrder", p.phaseOrder() == null ? List.of() : p.phaseOrder());
        body.put("phases", phasesToWire(p.phases()));
        body.put("status", p.status() == null ? CatalogStatus.PENDING_REVIEW.name() : p.status().name());
        body.put("provenance", provenanceToWire(p.provenance()));
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static Object provenanceToWire(CatalogProvenance pr) {
        if (pr == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("contributorId", pr.contributorId());
        m.put("promotedBy", pr.promotedBy());
        m.put("promotedAt", pr.promotedAt() == null ? null : pr.promotedAt().toString());
        m.put("rejectedReason", pr.rejectedReason());
        m.put("aliasOfId", pr.aliasOfId());
        return m;
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
            // targetStartDate/targetEndDate/completedAt are intentionally omitted —
            // a template carries no scheduling (the generalizer nulls them).
            m.put("days", FirestoreWorkoutProgramRepository.daysToWire(p.days()));
            out.add(m);
        }
        return out;
    }

    // ---- deserialization ----

    private CatalogWorkoutProgram toCatalog(DocumentSnapshot s) {
        return new CatalogWorkoutProgram(
            s.getId(),
            s.getString("title"),
            s.getString("description"),
            scheduleFromWire(s.get("schedule")),
            asStringList(s.get("phaseOrder")),
            phasesFromWire(s.get("phases")),
            enumOr(s.getString("status"), CatalogStatus.class, CatalogStatus.PENDING_REVIEW),
            provenanceFromWire(s.get("provenance")),
            toInstant(s.get("createdAt")),
            toInstant(s.get("updatedAt"))
        );
    }

    @SuppressWarnings("unchecked")
    private static CatalogProvenance provenanceFromWire(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> pm = (Map<String, Object>) m;
        String promotedAt = str(pm.get("promotedAt"));
        return new CatalogProvenance(
            str(pm.get("contributorId")),
            str(pm.get("promotedBy")),
            promotedAt == null ? null : Instant.parse(promotedAt),
            str(pm.get("rejectedReason")),
            str(pm.get("aliasOfId")));
    }

    @SuppressWarnings("unchecked")
    private static ProgramSchedule scheduleFromWire(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> sm = (Map<String, Object>) m;
        List<DayOfWeek> trainingDays = new ArrayList<>();
        if (sm.get("trainingDays") instanceof List<?> list) {
            for (Object o : list) {
                try { trainingDays.add(DayOfWeek.valueOf(String.valueOf(o))); }
                catch (IllegalArgumentException ignore) { }
            }
        }
        Map<DayOfWeek, String> locs = new EnumMap<>(DayOfWeek.class);
        if (sm.get("dayLocations") instanceof Map<?, ?> dl) {
            ((Map<String, Object>) dl).forEach((k, v) -> {
                try { locs.put(DayOfWeek.valueOf(k), String.valueOf(v)); }
                catch (IllegalArgumentException ignore) { }
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
                (LocalDate) null,
                (LocalDate) null,
                (Instant) null,
                FirestoreWorkoutProgramRepository.daysFromWire(pm.get("days"))
            ));
        }
        return out;
    }

    // ---- helpers ----

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static int asInt(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        return o instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static <E extends Enum<E>> E enumOr(String name, Class<E> type, E def) {
        if (name == null) return def;
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException e) { return def; }
    }
}
