package com.gte619n.healthfitness.persistence.nutrition;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.nutrition.DrinkOrderRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Stores the drink display order (IMPL-DRINK-01) as a {@code drinkOrder} string
 * array on the user document ({@code users/{uid}}), mirroring the merge-write
 * pattern used for {@code hiddenBiometrics}. No new top-level collection.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreDrinkOrderRepository implements DrinkOrderRepository {

    private static final String COLLECTION = "users";
    private static final String FIELD = "drinkOrder";

    private final Firestore firestore;

    public FirestoreDrinkOrderRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public List<String> getOrder(String userId) {
        DocumentSnapshot snapshot = await(firestore.collection(COLLECTION).document(userId).get());
        if (!snapshot.exists()) {
            return List.of();
        }
        Object raw = snapshot.get(FIELD);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) {
                ids.add(o.toString());
            }
        }
        return ids;
    }

    @Override
    public void saveOrder(String userId, List<String> orderedDrinkIds) {
        DocumentReference docRef = firestore.collection(COLLECTION).document(userId);
        Map<String, Object> body = new HashMap<>();
        body.put(FIELD, orderedDrinkIds == null ? List.of() : orderedDrinkIds);
        body.put("updatedAt", serverTimestamp());
        await(docRef.set(body, SetOptions.merge()));
    }
}
