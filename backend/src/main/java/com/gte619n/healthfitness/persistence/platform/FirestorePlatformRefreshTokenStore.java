package com.gte619n.healthfitness.persistence.platform;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.platform.PlatformRefreshToken;
import com.gte619n.healthfitness.core.platform.PlatformRefreshTokenStore;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Top-level `platformRefreshTokens/{tokenId}` (ADR-0020). Same flat, hash-only,
// single-read shape as the first-party `refreshTokens` store (ADR-0010/0019),
// plus the clientId/scopes a delegated grant carries. Rotation is an atomic
// check-and-set in a Firestore transaction.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestorePlatformRefreshTokenStore implements PlatformRefreshTokenStore {

    private static final String COLLECTION = "platformRefreshTokens";

    private final Firestore firestore;

    public FirestorePlatformRefreshTokenStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(PlatformRefreshToken token) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", token.userId());
        body.put("clientId", token.clientId());
        body.put("scopes", new ArrayList<>(token.scopes()));
        body.put("tokenHash", token.tokenHash());
        body.put("expiresAt", Timestamp.of(Date.from(token.expiresAt())));
        body.put("revoked", token.revoked());
        body.put("createdAt", serverTimestamp());
        await(firestore.collection(COLLECTION).document(token.tokenId()).set(body));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PlatformRefreshToken> findById(String tokenId) {
        DocumentSnapshot snap = await(firestore.collection(COLLECTION).document(tokenId).get());
        if (!snap.exists()) return Optional.empty();
        List<String> scopes = (List<String>) snap.get("scopes");
        return Optional.of(new PlatformRefreshToken(
            tokenId,
            snap.getString("userId"),
            snap.getString("clientId"),
            scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes),
            snap.getString("tokenHash"),
            toInstant(snap.get("createdAt")),
            toInstant(snap.get("expiresAt")),
            Boolean.TRUE.equals(snap.getBoolean("revoked")),
            toInstant(snap.get("rotatedAt")),
            snap.getString("replacedBy")
        ));
    }

    @Override
    public boolean tryMarkRotated(String tokenId, Instant rotatedAt, String successorId) {
        DocumentReference ref = firestore.collection(COLLECTION).document(tokenId);
        return await(firestore.runTransaction(txn -> {
            DocumentSnapshot snap = txn.get(ref).get();
            if (!snap.exists() || Boolean.TRUE.equals(snap.getBoolean("revoked"))) {
                return false;
            }
            Map<String, Object> body = new HashMap<>();
            body.put("revoked", true);
            body.put("rotatedAt", Timestamp.of(Date.from(rotatedAt)));
            body.put("replacedBy", successorId);
            txn.set(ref, body, SetOptions.merge());
            return true;
        }));
    }

    @Override
    public void repoint(String tokenId, String successorId) {
        await(firestore.collection(COLLECTION).document(tokenId)
            .set(Map.of("replacedBy", successorId), SetOptions.merge()));
    }

    @Override
    public void markRevoked(String tokenId) {
        await(firestore.collection(COLLECTION).document(tokenId)
            .set(Map.of("revoked", true), SetOptions.merge()));
    }

    @Override
    public void revokeAllForUser(String userId) {
        revokeLiveMatching(firestore.collection(COLLECTION)
            .whereEqualTo("userId", userId)
            .whereEqualTo("revoked", false));
    }

    @Override
    public void revokeForUserAndClient(String userId, String clientId) {
        revokeLiveMatching(firestore.collection(COLLECTION)
            .whereEqualTo("userId", userId)
            .whereEqualTo("clientId", clientId)
            .whereEqualTo("revoked", false));
    }

    private void revokeLiveMatching(com.google.cloud.firestore.Query query) {
        List<QueryDocumentSnapshot> docs = await(query.get()).getDocuments();
        if (docs.isEmpty()) return;
        WriteBatch batch = firestore.batch();
        for (QueryDocumentSnapshot doc : docs) {
            batch.set(doc.getReference(), Map.of("revoked", true), SetOptions.merge());
        }
        await(batch.commit());
    }
}
