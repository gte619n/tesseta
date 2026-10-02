package com.gte619n.healthfitness.testsupport.catalog;

import com.gte619n.healthfitness.core.adhoc.catalog.CatalogAdHocWorkout;
import com.gte619n.healthfitness.core.adhoc.catalog.CatalogAdHocWorkoutRepository;
import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCatalogAdHocWorkoutRepository implements CatalogAdHocWorkoutRepository {

    private final Map<String, CatalogAdHocWorkout> store = new ConcurrentHashMap<>();

    @Override
    public Optional<CatalogAdHocWorkout> findById(String catalogId) {
        return Optional.ofNullable(store.get(catalogId));
    }

    @Override
    public List<CatalogAdHocWorkout> findByStatus(CatalogStatus status) {
        return store.values().stream().filter(c -> c.status() == status).toList();
    }

    @Override
    public List<CatalogAdHocWorkout> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void save(CatalogAdHocWorkout workout) {
        store.put(workout.catalogId(), workout);
    }
}
