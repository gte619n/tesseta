package com.gte619n.healthfitness.persistence.platform;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.platform.OAuthGrant;
import com.gte619n.healthfitness.core.platform.OAuthGrantStore;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Top-level `oauthGrants/{userId__clientId}` (ADR-0020). Standing consent
// records backing the Connected Apps screen. The composite id keeps a user's
// grant for a given client unique and a single direct read, while a
// `userId`-equality query lists everything a user has connected.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreOAuthGrantStore implements OAuthGrantStore {

    private static final String COLLECTION = "oauthGrants";

    private final Firestore firestore;

    public FirestoreOAuthGrantStore(Firestore firestore) {
        this.firestore = firestore;
    }

    private static String docId(String userId, String clientId) {
        return userId + "__" + clientId;
    }

    @Override
    public void save(OAuthGrant grant) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", grant.userId());
        body.put("clientId", grant.clientId());
        body.put("scopes", new ArrayList<>(grant.scopes()));
        body.put("grantedAt", grant.grantedAt() == null
            ? serverTimestamp()
            : com.google.cloud.Timestamp.of(java.util.Date.from(grant.grantedAt())));
        body.put("updatedAt", serverTimestamp());
        await(firestore.collection(COLLECTION)
            .document(docId(grant.userId(), grant.clientId())).set(body));
    }

    @Override
    public Optional<OAuthGrant> find(String userId, String clientId) {
        DocumentSnapshot snap = await(firestore.collection(COLLECTION)
            .document(docId(userId, clientId)).get());
        return snap.exists() ? Optional.of(map(snap)) : Optional.empty();
    }

    @Override
    public List<OAuthGrant> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(firestore.collection(COLLECTION)
            .whereEqualTo("userId", userId).get()).getDocuments();
        List<OAuthGrant> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot doc : docs) {
            out.add(map(doc));
        }
        return out;
    }

    @Override
    public List<OAuthGrant> findByClient(String clientId) {
        List<QueryDocumentSnapshot> docs = await(firestore.collection(COLLECTION)
            .whereEqualTo("clientId", clientId).get()).getDocuments();
        List<OAuthGrant> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot doc : docs) {
            out.add(map(doc));
        }
        return out;
    }

    @Override
    public void delete(String userId, String clientId) {
        await(firestore.collection(COLLECTION).document(docId(userId, clientId)).delete());
    }

    @SuppressWarnings("unchecked")
    private static OAuthGrant map(DocumentSnapshot snap) {
        List<String> scopes = (List<String>) snap.get("scopes");
        return new OAuthGrant(
            snap.getString("userId"),
            snap.getString("clientId"),
            scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes),
            toInstant(snap.get("grantedAt")),
            toInstant(snap.get("updatedAt"))
        );
    }
}
