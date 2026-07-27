package com.gte619n.healthfitness.persistence.platform;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.platform.PlatformRateLimitStore;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Distributed fixed-window rate-limit counters (ADR-0020, D18). Each
// (client:user, windowStart) is one `platformRateLimits/{key}_{windowStart}`
// document holding a `count`. The increment runs in a Firestore transaction so
// concurrent requests across instances agree on the count — the limit is exact
// cluster-wide, unlike the per-instance in-memory fallback.
//
// Old window documents are tiny and simply stop being read; a Firestore TTL
// policy on `expiresAt` can reap them (add one in infra if churn matters).
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestorePlatformRateLimitStore implements PlatformRateLimitStore {

    private static final String COLLECTION = "platformRateLimits";

    private final Firestore firestore;

    public FirestorePlatformRateLimitStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public long incrementAndGet(String key, long windowStartEpochSeconds) {
        DocumentReference ref = firestore.collection(COLLECTION)
            .document(key + "_" + windowStartEpochSeconds);
        return await(firestore.runTransaction(txn -> {
            DocumentSnapshot snap = txn.get(ref).get();
            long current = snap.exists() && snap.getLong("count") != null
                ? snap.getLong("count") : 0L;
            long next = current + 1;
            txn.set(ref, Map.of(
                "count", next,
                "windowStart", windowStartEpochSeconds,
                "key", key));
            return next;
        }));
    }
}
