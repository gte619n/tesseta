package com.gte619n.healthfitness.testsupport.adhoc;

import com.gte619n.healthfitness.core.adhoc.AdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.AdHocWorkoutRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link AdHocWorkoutRepository} for unit tests (mirrors Firestore semantics). */
public class InMemoryAdHocWorkoutRepository implements AdHocWorkoutRepository {

    private final Map<String, Map<String, AdHocWorkout>> byUser = new ConcurrentHashMap<>();
    private final Set<String> archived = ConcurrentHashMap.newKeySet(); // "userId/adhocId"

    private Map<String, AdHocWorkout> forUser(String userId) {
        return byUser.computeIfAbsent(userId, k -> new LinkedHashMap<>());
    }

    private static String key(String userId, String adhocId) {
        return userId + "/" + adhocId;
    }

    @Override
    public Optional<AdHocWorkout> findById(String userId, String adhocId) {
        if (archived.contains(key(userId, adhocId))) {
            return Optional.empty();
        }
        return Optional.ofNullable(forUser(userId).get(adhocId));
    }

    @Override
    public List<AdHocWorkout> findByUser(String userId) {
        List<AdHocWorkout> out = new ArrayList<>();
        for (AdHocWorkout w : forUser(userId).values()) {
            if (!archived.contains(key(userId, w.adhocId()))) {
                out.add(w);
            }
        }
        return out;
    }

    @Override
    public List<AdHocWorkout> findByUserIncludingArchived(String userId) {
        return new ArrayList<>(forUser(userId).values());
    }

    @Override
    public void save(AdHocWorkout w) {
        forUser(w.userId()).put(w.adhocId(), w);
        archived.remove(key(w.userId(), w.adhocId())); // a save writes syncStatus=ACTIVE
    }

    @Override
    public void archive(String userId, String adhocId) {
        archived.add(key(userId, adhocId));
    }

    @Override
    public void restore(String userId, String adhocId) {
        archived.remove(key(userId, adhocId));
    }
}
