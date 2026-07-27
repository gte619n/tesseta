package com.gte619n.healthfitness.persistence.platform;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.platform.WebhookCheckpointStore;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// `webhookCheckpoints/{clientId__userId}` (ADR-0020, D19). The delivery
// high-water mark per (client, user).
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreWebhookCheckpointStore implements WebhookCheckpointStore {

    private static final String COLLECTION = "webhookCheckpoints";

    private final Firestore firestore;

    public FirestoreWebhookCheckpointStore(Firestore firestore) {
        this.firestore = firestore;
    }

    private static String docId(String clientId, String userId) {
        return clientId + "__" + userId;
    }

    @Override
    public Optional<Instant> find(String clientId, String userId) {
        DocumentSnapshot snap = await(firestore.collection(COLLECTION)
            .document(docId(clientId, userId)).get());
        if (!snap.exists()) return Optional.empty();
        return Optional.ofNullable(toInstant(snap.get("deliveredThrough")));
    }

    @Override
    public void save(String clientId, String userId, Instant deliveredThrough) {
        await(firestore.collection(COLLECTION).document(docId(clientId, userId))
            .set(Map.of("deliveredThrough", Timestamp.of(Date.from(deliveredThrough)))));
    }
}
