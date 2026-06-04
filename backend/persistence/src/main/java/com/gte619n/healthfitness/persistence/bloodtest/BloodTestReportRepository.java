package com.gte619n.healthfitness.persistence.bloodtest;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Persists blood test reports at users/{userId}/bloodTestReports/{reportId}.
// Markers are stored as a nested list of maps.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class BloodTestReportRepository implements com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository {

    private static final String SUBCOLLECTION = "bloodTestReports";

    private final Firestore firestore;

    public BloodTestReportRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(BloodTestReport report) {
        DocumentReference docRef = collection(report.userId()).document(report.reportId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(report, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public Optional<BloodTestReport> findById(String userId, String reportId) {
        DocumentSnapshot snap = await(collection(userId).document(reportId).get());
        if (!snap.exists() || isArchived(snap)) return Optional.empty();
        return Optional.of(toReport(userId, snap));
    }

    @Override
    public List<BloodTestReport> findByUser(String userId) {
        // Fetch all docs (no orderBy to include null sampleDate), sort in Java.
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .limit(200)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toReport(userId, d))
            .sorted((a, b) -> {
                // nulls last, then descending by sampleDate
                if (a.sampleDate() == null && b.sampleDate() == null) return 0;
                if (a.sampleDate() == null) return 1;
                if (b.sampleDate() == null) return -1;
                return b.sampleDate().compareTo(a.sampleDate());
            })
            .toList();
    }

    @Override
    public Optional<BloodTestReport> findByContentHash(String userId, String contentHash) {
        if (contentHash == null) return Optional.empty();
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("contentHash", contentHash)
            .limit(10)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .findFirst()
            .map(d -> toReport(userId, d));
    }

    @Override
    public void delete(String userId, String reportId) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2.
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId).document(reportId).set(updates, SetOptions.merge()));
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(BloodTestReport r, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("sampleDate", r.sampleDate() == null ? null : r.sampleDate().toString());
        body.put("labSource", r.labSource());
        body.put("pdfStoragePath", r.pdfStoragePath());
        body.put("contentHash", r.contentHash());

        List<Map<String, Object>> markerMaps = new ArrayList<>();
        if (r.markers() != null) {
            for (ExtractedMarker m : r.markers()) {
                Map<String, Object> mm = new HashMap<>();
                mm.put("name", m.name());
                mm.put("value", m.value());
                mm.put("unit", m.unit());
                mm.put("refRangeLow", m.refRangeLow());
                mm.put("refRangeHigh", m.refRangeHigh());
                mm.put("flag", m.flag());
                markerMaps.add(mm);
            }
        }
        body.put("markers", markerMaps);

        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) body.put("createdAt", serverTimestamp());
        return body;
    }

    @SuppressWarnings("unchecked")
    private static BloodTestReport toReport(String userId, DocumentSnapshot s) {
        String sampleDateStr = s.getString("sampleDate");
        List<Map<String, Object>> markerMaps = (List<Map<String, Object>>) s.get("markers");
        List<ExtractedMarker> markers = new ArrayList<>();
        if (markerMaps != null) {
            for (Map<String, Object> mm : markerMaps) {
                markers.add(new ExtractedMarker(
                    (String) mm.get("name"),
                    asDouble(mm.get("value")),
                    (String) mm.get("unit"),
                    asDouble(mm.get("refRangeLow")),
                    asDouble(mm.get("refRangeHigh")),
                    (String) mm.get("flag")
                ));
            }
        }
        return new BloodTestReport(
            userId,
            s.getId(),
            sampleDateStr == null ? null : LocalDate.parse(sampleDateStr),
            s.getString("labSource"),
            s.getString("pdfStoragePath"),
            s.getString("contentHash"),
            markers,
            toInstant(s.get("createdAt")),
            toInstant(s.get("updatedAt"))
        );
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

}
