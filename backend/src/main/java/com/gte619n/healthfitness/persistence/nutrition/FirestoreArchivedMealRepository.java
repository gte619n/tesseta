package com.gte619n.healthfitness.persistence.nutrition;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.nutrition.ArchivedMealRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Per-user archive list: users/{userId}/archivedMeals/{mealId}. A tombstone
// marker (no meal data) that the meal search filters against — the meal itself
// lives in the shared mealCatalog collection and is never mutated or deleted.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreArchivedMealRepository implements ArchivedMealRepository {

    private static final String USERS = "users";
    private static final String ARCHIVED_MEALS = "archivedMeals";

    private final Firestore firestore;

    public FirestoreArchivedMealRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void archive(String userId, String mealId) {
        Map<String, Object> body = new HashMap<>();
        body.put("archivedAt", serverTimestamp());
        await(collection(userId).document(mealId).set(body, SetOptions.merge()));
    }

    @Override
    public Set<String> archivedMealIds(String userId) {
        Set<String> ids = new LinkedHashSet<>();
        for (QueryDocumentSnapshot doc : await(collection(userId).get()).getDocuments()) {
            ids.add(doc.getId());
        }
        return ids;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection(USERS).document(userId).collection(ARCHIVED_MEALS);
    }
}
