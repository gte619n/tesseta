package com.gte619n.healthfitness.persistence.gym;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.equipment.BulkImportService.PreviewResult;
import com.gte619n.healthfitness.core.gym.EquipmentScan;
import com.gte619n.healthfitness.core.gym.ScanStatus;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * IMPL-GYM-003: Firestore-backed {@link EquipmentScan} store at
 * {@code users/{userId}/locations/{locationId}/equipmentScans/{scanId}}.
 *
 * <p>The {@code preview} (a nested bulk-import result) is stored as a JSON blob
 * rather than hand-mapped to nested Firestore maps — it is a transient,
 * read-once-by-the-owner artifact, so a compact serialized form is simplest and
 * avoids drift with the {@code PreviewResult} shape.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class EquipmentScanRepository
    implements com.gte619n.healthfitness.core.gym.EquipmentScanRepository {

    private static final Logger log = LoggerFactory.getLogger(EquipmentScanRepository.class);

    private final Firestore firestore;
    private final ObjectMapper json;

    public EquipmentScanRepository(Firestore firestore, ObjectMapper json) {
        this.firestore = firestore;
        this.json = json;
    }

    private CollectionReference collection(String userId, String locationId) {
        return firestore.collection("users").document(userId)
            .collection("locations").document(locationId)
            .collection("equipmentScans");
    }

    @Override
    public void save(EquipmentScan scan) {
        DocumentReference docRef = collection(scan.userId(), scan.locationId())
            .document(scan.scanId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = new HashMap<>();
        body.put("status", scan.status().name());
        body.put("videoRef", scan.videoRef());
        body.put("mimeType", scan.mimeType());
        body.put("sizeBytes", scan.sizeBytes());
        body.put("detectedCount", scan.detectedCount());
        body.put("error", scan.error());
        body.put("previewJson", writePreview(scan.preview()));
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (!existing.exists()) {
            body.put("createdAt", serverTimestamp());
        }
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public Optional<EquipmentScan> findById(String userId, String locationId, String scanId) {
        DocumentSnapshot snap = await(collection(userId, locationId).document(scanId).get());
        if (!snap.exists() || isArchived(snap)) {
            return Optional.empty();
        }
        Long size = snap.getLong("sizeBytes");
        Long count = snap.getLong("detectedCount");
        return Optional.of(new EquipmentScan(
            userId,
            locationId,
            snap.getId(),
            statusOf(snap),
            snap.getString("videoRef"),
            snap.getString("mimeType"),
            size,
            count == null ? null : count.intValue(),
            readPreview(snap.getString("previewJson")),
            snap.getString("error"),
            toInstant(snap.get("createdAt")),
            toInstant(snap.get("updatedAt"))
        ));
    }

    private static ScanStatus statusOf(DocumentSnapshot snap) {
        String raw = snap.getString("status");
        if (raw == null) {
            return ScanStatus.REGISTERED;
        }
        try {
            return ScanStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ScanStatus.REGISTERED;
        }
    }

    private String writePreview(PreviewResult preview) {
        if (preview == null) {
            return null;
        }
        try {
            return json.writeValueAsString(preview);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to serialize scan preview: {}", e.toString());
            return null;
        }
    }

    private PreviewResult readPreview(String previewJson) {
        if (previewJson == null || previewJson.isBlank()) {
            return null;
        }
        try {
            return json.readValue(previewJson, PreviewResult.class);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to deserialize scan preview: {}", e.toString());
            return null;
        }
    }
}
