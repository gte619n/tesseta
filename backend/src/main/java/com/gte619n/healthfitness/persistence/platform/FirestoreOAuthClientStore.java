package com.gte619n.healthfitness.persistence.platform;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.platform.OAuthClient;
import com.gte619n.healthfitness.core.platform.OAuthClientStore;
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

// Top-level `oauthClients/{clientId}` (ADR-0020). Registered third-party apps.
// Gated on the shared Firestore switch so unit tests use an in-memory fake.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreOAuthClientStore implements OAuthClientStore {

    private static final String COLLECTION = "oauthClients";

    private final Firestore firestore;

    public FirestoreOAuthClientStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(OAuthClient client) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", client.name());
        body.put("logoUrl", client.logoUrl());
        body.put("redirectUris", client.redirectUris());
        body.put("allowedScopes", new ArrayList<>(client.allowedScopes()));
        body.put("secretHash", client.secretHash());
        body.put("createdAt", serverTimestamp());
        await(firestore.collection(COLLECTION).document(client.clientId()).set(body));
    }

    @Override
    public Optional<OAuthClient> findById(String clientId) {
        DocumentSnapshot snap = await(firestore.collection(COLLECTION).document(clientId).get());
        return snap.exists() ? Optional.of(map(snap)) : Optional.empty();
    }

    @Override
    public List<OAuthClient> findAll() {
        List<QueryDocumentSnapshot> docs =
            await(firestore.collection(COLLECTION).get()).getDocuments();
        List<OAuthClient> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot doc : docs) {
            out.add(map(doc));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static OAuthClient map(DocumentSnapshot snap) {
        List<String> redirectUris = (List<String>) snap.get("redirectUris");
        List<String> scopes = (List<String>) snap.get("allowedScopes");
        return new OAuthClient(
            snap.getId(),
            snap.getString("name"),
            snap.getString("logoUrl"),
            redirectUris == null ? List.of() : redirectUris,
            scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes),
            snap.getString("secretHash"),
            toInstant(snap.get("createdAt"))
        );
    }
}
