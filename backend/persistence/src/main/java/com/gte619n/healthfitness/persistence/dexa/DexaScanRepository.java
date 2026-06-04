package com.gte619n.healthfitness.persistence.dexa;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.dexa.DexaRegion;
import com.gte619n.healthfitness.core.dexa.DexaScan;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Persists DEXA scans at users/{userId}/dexaScans/{scanId}. Region rows
// are stored as nested maps under fixed keys so reads can pull the whole
// scan in one round-trip.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class DexaScanRepository implements com.gte619n.healthfitness.core.dexa.DexaScanRepository {

    private static final String SUBCOLLECTION = "dexaScans";

    private final Firestore firestore;

    public DexaScanRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(DexaScan scan) {
        DocumentReference docRef = collection(scan.userId()).document(scan.scanId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(scan, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public Optional<DexaScan> findById(String userId, String scanId) {
        DocumentSnapshot snap = await(collection(userId).document(scanId).get());
        if (!snap.exists() || isArchived(snap)) return Optional.empty();
        return Optional.of(toScan(userId, snap));
    }

    @Override
    public List<DexaScan> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("measuredOn", Query.Direction.DESCENDING)
            .limit(200)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toScan(userId, d))
            .toList();
    }

    @Override
    public Optional<DexaScan> findByContentHash(String userId, String contentHash) {
        if (contentHash == null) return Optional.empty();
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("contentHash", contentHash)
            .limit(10)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .findFirst()
            .map(d -> toScan(userId, d));
    }

    @Override
    public void delete(String userId, String scanId) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2.
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId).document(scanId).set(updates, SetOptions.merge()));
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(DexaScan s, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("measuredOn", s.measuredOn() == null ? null : s.measuredOn().toString());
        body.put("sourceFacility", s.sourceFacility());
        body.put("pdfStoragePath", s.pdfStoragePath());
        body.put("contentHash", s.contentHash());

        body.put("totalMassLb", s.totalMassLb());
        body.put("leanTissueLb", s.leanTissueLb());
        body.put("fatTissueLb", s.fatTissueLb());
        body.put("totalBodyFatPercent", s.totalBodyFatPercent());

        body.put("visceralFatLb", s.visceralFatLb());
        body.put("androidGynoidRatio", s.androidGynoidRatio());

        body.put("trunk", regionToMap(s.trunk()));
        body.put("android", regionToMap(s.android()));
        body.put("gynoid", regionToMap(s.gynoid()));
        body.put("armsTotal", regionToMap(s.armsTotal()));
        body.put("armsRight", regionToMap(s.armsRight()));
        body.put("armsLeft", regionToMap(s.armsLeft()));
        body.put("legsTotal", regionToMap(s.legsTotal()));
        body.put("legsRight", regionToMap(s.legsRight()));
        body.put("legsLeft", regionToMap(s.legsLeft()));

        body.put("bmdTScore", s.bmdTScore());
        body.put("bmdZScore", s.bmdZScore());

        body.put("restingMetabolicRateKcal", s.restingMetabolicRateKcal());

        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) body.put("createdAt", serverTimestamp());
        return body;
    }

    private static Map<String, Object> regionToMap(DexaRegion r) {
        if (r == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("totalMassLb", r.totalMassLb());
        m.put("leanTissueLb", r.leanTissueLb());
        m.put("fatTissueLb", r.fatTissueLb());
        m.put("regionFatPercent", r.regionFatPercent());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static DexaRegion regionFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        return new DexaRegion(
            asDouble(m.get("totalMassLb")),
            asDouble(m.get("leanTissueLb")),
            asDouble(m.get("fatTissueLb")),
            asDouble(m.get("regionFatPercent"))
        );
    }

    private static DexaScan toScan(String userId, DocumentSnapshot s) {
        String measuredOn = s.getString("measuredOn");
        return new DexaScan(
            userId,
            s.getId(),
            measuredOn == null ? null : LocalDate.parse(measuredOn),
            s.getString("sourceFacility"),
            s.getString("pdfStoragePath"),
            s.getString("contentHash"),
            s.getDouble("totalMassLb"),
            s.getDouble("leanTissueLb"),
            s.getDouble("fatTissueLb"),
            s.getDouble("totalBodyFatPercent"),
            s.getDouble("visceralFatLb"),
            s.getDouble("androidGynoidRatio"),
            regionFromMap(s.get("trunk")),
            regionFromMap(s.get("android")),
            regionFromMap(s.get("gynoid")),
            regionFromMap(s.get("armsTotal")),
            regionFromMap(s.get("armsRight")),
            regionFromMap(s.get("armsLeft")),
            regionFromMap(s.get("legsTotal")),
            regionFromMap(s.get("legsRight")),
            regionFromMap(s.get("legsLeft")),
            s.getDouble("bmdTScore"),
            s.getDouble("bmdZScore"),
            s.getLong("restingMetabolicRateKcal") == null
                ? null
                : s.getLong("restingMetabolicRateKcal").intValue(),
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
