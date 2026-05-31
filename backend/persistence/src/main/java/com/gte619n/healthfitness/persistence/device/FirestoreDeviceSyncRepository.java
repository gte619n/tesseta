package com.gte619n.healthfitness.persistence.device;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.device.DeviceSync;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Persists per-platform sync timestamps at
//   users/{userId}/deviceSyncs/{platform}
// The document id is the source platform, so repeated syncs from the same
// platform idempotently overwrite the same doc.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreDeviceSyncRepository implements DeviceSyncRepository {

    private static final String SUBCOLLECTION = "deviceSyncs";

    private final Firestore firestore;

    public FirestoreDeviceSyncRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void recordSync(String userId, String platform, Instant syncedAt) {
        if (platform == null || platform.isBlank()) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("platform", platform);
        body.put("lastSyncedAt", Timestamp.of(java.util.Date.from(syncedAt)));
        body.put("updatedAt", serverTimestamp());
        await(collection(userId).document(platform).set(body, SetOptions.merge()));
    }

    @Override
    public List<DeviceSync> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId).get()).getDocuments();
        return docs.stream()
            .map(d -> new DeviceSync(d.getString("platform"), toInstant(d.get("lastSyncedAt"))))
            .toList();
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
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
