package com.gte619n.healthfitness.persistence.user;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.user.GoogleHealthConnection;
import com.gte619n.healthfitness.core.user.User;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.Blob;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Gated on the same property as FirestoreConfig so unit tests can run
// without a Firestore bean wired up.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class UserRepository implements com.gte619n.healthfitness.core.user.UserRepository {

    private static final String COLLECTION = "users";

    private final Firestore firestore;

    public UserRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<User> findById(String userId) {
        DocumentSnapshot snapshot = await(firestore.collection(COLLECTION).document(userId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toUser(userId, snapshot));
    }

    @Override
    public Optional<User> findByHealthUserId(String healthUserId) {
        List<QueryDocumentSnapshot> docs = await(firestore.collection(COLLECTION)
            .whereEqualTo("googleHealth.healthUserId", healthUserId)
            .limit(1)
            .get()).getDocuments();
        if (docs.isEmpty()) return Optional.empty();
        QueryDocumentSnapshot snapshot = docs.get(0);
        return Optional.of(toUser(snapshot.getId(), snapshot));
    }

    @Override
    public void save(User user) {
        var docRef = firestore.collection(COLLECTION).document(user.userId());
        DocumentSnapshot existing = await(docRef.get());

        Map<String, Object> body = new HashMap<>();
        body.put("email", user.email());
        body.put("displayName", user.displayName());
        body.put("updatedAt", serverTimestamp());
        if (!existing.exists()) {
            body.put("createdAt", serverTimestamp());
        }
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void recordGoogleHealthConnection(String userId, GoogleHealthConnection connection) {
        var docRef = firestore.collection(COLLECTION).document(userId);
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> gh = new HashMap<>();
        gh.put("healthUserId", connection.healthUserId());
        gh.put("refreshTokenCiphertext", Blob.fromBytes(connection.refreshTokenCiphertext()));
        gh.put("dekCiphertext", Blob.fromBytes(connection.dekCiphertext()));
        gh.put("connectedAt", serverTimestamp());
        body.put("googleHealth", gh);
        body.put("updatedAt", serverTimestamp());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void updateHeightCm(String userId, Integer heightCm) {
        var docRef = firestore.collection(COLLECTION).document(userId);
        Map<String, Object> body = new HashMap<>();
        body.put("heightCm", heightCm == null
            ? com.google.cloud.firestore.FieldValue.delete()
            : heightCm);
        body.put("updatedAt", serverTimestamp());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void clearGoogleHealthConnection(String userId) {
        var docRef = firestore.collection(COLLECTION).document(userId);
        Map<String, Object> body = new HashMap<>();
        body.put("googleHealth", com.google.cloud.firestore.FieldValue.delete());
        body.put("updatedAt", serverTimestamp());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public List<String> findAllUserIds() {
        // listDocuments() walks the entire users/ collection and returns
        // every top-level document reference — including ones that exist
        // only as parents of subcollections. Used by the daily SUSTAINED
        // re-evaluation Cloud Run Job (IMPL-12 Phase 5).
        List<String> ids = new ArrayList<>();
        for (DocumentReference ref : firestore.collection(COLLECTION).listDocuments()) {
            ids.add(ref.getId());
        }
        return ids;
    }

    private static User toUser(String userId, DocumentSnapshot snapshot) {
        Long heightLong = snapshot.getLong("heightCm");
        Integer heightCm = heightLong == null ? null : heightLong.intValue();
        return new User(
            userId,
            snapshot.getString("email"),
            snapshot.getString("displayName"),
            toGoogleHealth(snapshot),
            heightCm,
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    @SuppressWarnings("unchecked")
    private static GoogleHealthConnection toGoogleHealth(DocumentSnapshot snapshot) {
        Object raw = snapshot.get("googleHealth");
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> gh = (Map<String, Object>) map;
        Object refreshCt = gh.get("refreshTokenCiphertext");
        Object dekCt = gh.get("dekCiphertext");
        return new GoogleHealthConnection(
            (String) gh.get("healthUserId"),
            refreshCt instanceof Blob b ? b.toBytes() : null,
            dekCt instanceof Blob b ? b.toBytes() : null,
            toInstant(gh.get("connectedAt"))
        );
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
