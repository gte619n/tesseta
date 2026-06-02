package com.gte619n.healthfitness.persistence.sync;

import com.gte619n.healthfitness.core.sync.IdempotencyStore;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Durable, instance-shared {@link IdempotencyStore} backed by Firestore
 * (IMPL-AND-20, D7). Records live at
 * {@code users/{uid}/idempotencyKeys/{scope#key}} with an {@code expiresAt}
 * timestamp; a {@code Firestore TTL policy} on that field reaps expired records
 * automatically (declare {@code idempotencyKeys.expiresAt} as a TTL field in the
 * Firestore console / Terraform — see plan doc). Reads also enforce the TTL
 * in-app so an unreaped-but-expired key still behaves as absent.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreIdempotencyStore implements IdempotencyStore {

    private static final String USERS = "users";
    private static final String SUBCOLLECTION = "idempotencyKeys";
    private static final String RESULT_ID = "resultId";
    private static final String EXPIRES_AT = "expiresAt";

    private final Firestore firestore;

    public FirestoreIdempotencyStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<String> findResult(String userId, String scope, String key) {
        DocumentSnapshot snap = await(docRef(userId, scope, key).get());
        if (!snap.exists()) {
            return Optional.empty();
        }
        Object expires = snap.get(EXPIRES_AT);
        if (expires instanceof Timestamp ts && ts.toDate().toInstant().isBefore(Instant.now())) {
            // Expired but not yet reaped — treat as absent.
            return Optional.empty();
        }
        return Optional.ofNullable(snap.getString(RESULT_ID));
    }

    @Override
    public void record(String userId, String scope, String key, String resultId) {
        Map<String, Object> body = new HashMap<>();
        body.put(RESULT_ID, resultId);
        body.put(EXPIRES_AT, Timestamp.of(java.util.Date.from(Instant.now().plus(TTL))));
        await(docRef(userId, scope, key).set(body));
    }

    private DocumentReference docRef(String userId, String scope, String key) {
        return firestore.collection(USERS).document(userId)
            .collection(SUBCOLLECTION).document(documentId(scope, key));
    }

    /**
     * Firestore document ids may not contain '/'. Scope and key are joined with
     * a '#' (scopes are fixed literals like {@code bloodReadings:create}; keys
     * are client UUIDs) — neither contains '#'.
     */
    private static String documentId(String scope, String key) {
        return scope + "#" + key;
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
