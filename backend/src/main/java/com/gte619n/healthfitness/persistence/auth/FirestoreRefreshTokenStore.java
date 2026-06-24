package com.gte619n.healthfitness.persistence.auth;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.auth.RefreshTokenStore;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Top-level `refreshTokens/{tokenId}` collection (ADR-0010). Kept flat rather
// than under users/{uid} so a refresh is a single direct read keyed by the
// tokenId the client carries — no per-user subcollection scan. Only the secret
// hash is stored; the secret itself never touches Firestore.
//
// Gated on the same property as the other Firestore repositories so unit tests
// run against an in-memory fake instead.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreRefreshTokenStore implements RefreshTokenStore {

    private static final String COLLECTION = "refreshTokens";

    private final Firestore firestore;

    public FirestoreRefreshTokenStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(StoredRefreshToken token) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", token.userId());
        body.put("tokenHash", token.tokenHash());
        body.put("expiresAt", Timestamp.of(java.util.Date.from(token.expiresAt())));
        body.put("revoked", token.revoked());
        body.put("createdAt", serverTimestamp());
        await(firestore.collection(COLLECTION).document(token.tokenId()).set(body));
    }

    @Override
    public Optional<StoredRefreshToken> findById(String tokenId) {
        DocumentSnapshot snap = await(firestore.collection(COLLECTION).document(tokenId).get());
        if (!snap.exists()) return Optional.empty();
        return Optional.of(new StoredRefreshToken(
            tokenId,
            snap.getString("userId"),
            snap.getString("tokenHash"),
            toInstant(snap.get("createdAt")),
            toInstant(snap.get("expiresAt")),
            Boolean.TRUE.equals(snap.getBoolean("revoked")),
            toInstant(snap.get("rotatedAt"))
        ));
    }

    @Override
    public void markRotated(String tokenId, java.time.Instant rotatedAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("revoked", true);
        body.put("rotatedAt", Timestamp.of(java.util.Date.from(rotatedAt)));
        await(firestore.collection(COLLECTION).document(tokenId).set(body, SetOptions.merge()));
    }

    @Override
    public void markRevoked(String tokenId) {
        Map<String, Object> body = new HashMap<>();
        body.put("revoked", true);
        // No rotatedAt: logout is definitive and must not be reuse-grace eligible.
        await(firestore.collection(COLLECTION).document(tokenId).set(body, SetOptions.merge()));
    }

    @Override
    public void revokeAllForUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(firestore.collection(COLLECTION)
            .whereEqualTo("userId", userId)
            .whereEqualTo("revoked", false)
            .get()).getDocuments();
        if (docs.isEmpty()) return;
        WriteBatch batch = firestore.batch();
        for (QueryDocumentSnapshot doc : docs) {
            batch.set(doc.getReference(), Map.of("revoked", true), SetOptions.merge());
        }
        await(batch.commit());
    }
}
