package com.gte619n.healthfitness.persistence.platform;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.platform.WebhookSubscription;
import com.gte619n.healthfitness.core.platform.WebhookSubscriptionStore;
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

// `webhookSubscriptions/{clientId}` (ADR-0020). One subscription per client. The
// HMAC signing secret is NOT stored — it is derived from a master key at
// delivery (WebhookSecrets) — so this holds only url + events + active.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreWebhookSubscriptionStore implements WebhookSubscriptionStore {

    private static final String COLLECTION = "webhookSubscriptions";

    private final Firestore firestore;

    public FirestoreWebhookSubscriptionStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(WebhookSubscription s) {
        Map<String, Object> body = new HashMap<>();
        body.put("url", s.url());
        body.put("eventTypes", new ArrayList<>(s.eventTypes()));
        body.put("active", s.active());
        body.put("updatedAt", serverTimestamp());
        body.put("createdAt", s.createdAt() == null ? serverTimestamp()
            : com.google.cloud.Timestamp.of(java.util.Date.from(s.createdAt())));
        await(firestore.collection(COLLECTION).document(s.clientId()).set(body));
    }

    @Override
    public Optional<WebhookSubscription> findByClientId(String clientId) {
        DocumentSnapshot snap = await(firestore.collection(COLLECTION).document(clientId).get());
        return snap.exists() ? Optional.of(map(snap)) : Optional.empty();
    }

    @Override
    public List<WebhookSubscription> findAllActive() {
        List<QueryDocumentSnapshot> docs = await(firestore.collection(COLLECTION)
            .whereEqualTo("active", true).get()).getDocuments();
        List<WebhookSubscription> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot doc : docs) {
            out.add(map(doc));
        }
        return out;
    }

    @Override
    public void delete(String clientId) {
        await(firestore.collection(COLLECTION).document(clientId).delete());
    }

    @SuppressWarnings("unchecked")
    private static WebhookSubscription map(DocumentSnapshot snap) {
        List<String> events = (List<String>) snap.get("eventTypes");
        return new WebhookSubscription(
            snap.getId(),
            snap.getString("url"),
            events == null ? new LinkedHashSet<>() : new LinkedHashSet<>(events),
            Boolean.TRUE.equals(snap.getBoolean("active")),
            toInstant(snap.get("createdAt")),
            toInstant(snap.get("updatedAt")));
    }
}
