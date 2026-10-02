package com.gte619n.healthfitness.testsupport.catalog;

import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import com.gte619n.healthfitness.core.workoutprogram.catalog.CatalogWorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.catalog.CatalogWorkoutProgramRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCatalogWorkoutProgramRepository implements CatalogWorkoutProgramRepository {

    private final Map<String, CatalogWorkoutProgram> store = new ConcurrentHashMap<>();

    @Override
    public Optional<CatalogWorkoutProgram> findById(String catalogId) {
        return Optional.ofNullable(store.get(catalogId));
    }

    @Override
    public List<CatalogWorkoutProgram> findByStatus(CatalogStatus status) {
        return store.values().stream().filter(c -> c.status() == status).toList();
    }

    @Override
    public List<CatalogWorkoutProgram> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void save(CatalogWorkoutProgram program) {
        store.put(program.catalogId(), program);
    }
}
