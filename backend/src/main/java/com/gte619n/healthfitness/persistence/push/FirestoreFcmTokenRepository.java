package com.gte619n.healthfitness.persistence.push;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.push.FcmToken;
import com.gte619n.healthfitness.core.push.FcmTokenRepository;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Per-user FCM token registry persisted at
 *   users/{userId}/fcmTokens/{deviceId}
 * (IMPL-AND-20, D18). The document id is the client device id, so a refresh from
 * the same device idempotently overwrites the same doc. {@code updatedAt} is
 * stamped with the server clock on every register/refresh.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreFcmTokenRepository implements FcmTokenRepository {

    private static final String SUBCOLLECTION = "fcmTokens";

    private final Firestore firestore;

    public FirestoreFcmTokenRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(String userId, FcmToken token) {
        if (token == null || token.deviceId() == null || token.deviceId().isBlank()
            || token.token() == null || token.token().isBlank()) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", token.deviceId());
        body.put("token", token.token());
        body.put("updatedAt", serverTimestamp());
        await(collection(userId).document(token.deviceId()).set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        await(collection(userId).document(deviceId).delete());
    }

    @Override
    public List<FcmToken> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId).get()).getDocuments();
        return docs.stream()
            .map(d -> new FcmToken(
                d.getId(),
                d.getString("token"),
                toInstant(d.get("updatedAt"))))
            .filter(t -> t.token() != null && !t.token().isBlank())
            .toList();
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

}
