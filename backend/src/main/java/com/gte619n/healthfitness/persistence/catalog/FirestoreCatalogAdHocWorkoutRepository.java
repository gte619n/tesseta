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
import com.gte619n.healthfitness.core.adhoc.catalog.CatalogAdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.catalog.CatalogAdHocWorkoutRepository;
import com.gte619n.healthfitness.core.catalog.CatalogProvenance;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.persistence.workoutprogram.FirestoreWorkoutProgramRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed shared ad-hoc templates at top-level
 * {@code adhocCatalog/{catalogId}} (IMPL-MULTIUSER-01 P3.3 / D9). The single
 * embedded {@link WorkoutDay} is (de)serialized via the shared program wire
 * helpers.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreCatalogAdHocWorkoutRepository implements CatalogAdHocWorkoutRepository {

    private final Firestore firestore;

    public FirestoreCatalogAdHocWorkoutRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection() {
        return firestore.collection("adhocCatalog");
    }

    @Override
    public Optional<CatalogAdHocWorkout> findById(String catalogId) {
        DocumentSnapshot snap = await(collection().document(catalogId).get());
        return snap.exists() ? Optional.of(toCatalog(snap)) : Optional.empty();
    }

    @Override
    public List<CatalogAdHocWorkout> findByStatus(CatalogStatus status) {
        List<QueryDocumentSnapshot> docs =
            await(collection().whereEqualTo("status", status.name()).limit(500).get()).getDocuments();
        return docs.stream().map(this::toCatalog).toList();
    }

    @Override
    public List<CatalogAdHocWorkout> findAll() {
        List<QueryDocumentSnapshot> docs = await(collection().limit(500).get()).getDocuments();
        return docs.stream().map(this::toCatalog).toList();
    }

    @Override
    public void save(CatalogAdHocWorkout w) {
        DocumentReference ref = collection().document(w.catalogId());
        boolean isNew = !await(ref.get()).exists();
        await(ref.set(toBody(w, isNew), SetOptions.merge()));
    }

    // ---- serialization ----

    private static Map<String, Object> toBody(CatalogAdHocWorkout w, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", w.title());
        body.put("summary", w.summary());
        body.put("tags", w.tags() == null ? List.of() : w.tags());
        // One embedded day; reuse the shared day wire helper (wrap in a 1-list).
        body.put("day", w.day() == null ? null
            : FirestoreWorkoutProgramRepository.daysToWire(List.of(w.day())).get(0));
        body.put("targetDurationMinutes", w.targetDurationMinutes());
        body.put("estimatedDurationSeconds", w.estimatedDurationSeconds());
        body.put("status", w.status() == null ? CatalogStatus.PENDING_REVIEW.name() : w.status().name());
        body.put("provenance", provenanceToWire(w.provenance()));
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

    // ---- deserialization ----

    private CatalogAdHocWorkout toCatalog(DocumentSnapshot s) {
        List<WorkoutDay> days = FirestoreWorkoutProgramRepository.daysFromWire(
            s.get("day") == null ? null : List.of(s.get("day")));
        WorkoutDay day = days.isEmpty() ? null : days.get(0);
        return new CatalogAdHocWorkout(
            s.getId(),
            s.getString("title"),
            s.getString("summary"),
            asStringList(s.get("tags")),
            day,
            intOrNull(s.get("targetDurationMinutes")),
            intOrNull(s.get("estimatedDurationSeconds")),
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

    // ---- helpers ----

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Integer intOrNull(Object o) { return o instanceof Number n ? n.intValue() : null; }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        return o instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static <E extends Enum<E>> E enumOr(String name, Class<E> type, E def) {
        if (name == null) return def;
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException e) { return def; }
    }
}
