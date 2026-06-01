package com.gte619n.healthfitness.core.medication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for the shared drug catalog.
 */
public interface DrugRepository {
    Optional<Drug> findById(String drugId);

    /**
     * Resolve many drugs by id in as few round-trips as possible, returning a
     * map keyed by drugId (missing ids are simply absent). The default
     * implementation falls back to per-id {@link #findById} lookups so test
     * fakes work unchanged; the Firestore implementation overrides this with a
     * batched query to avoid N reads.
     */
    default Map<String, Drug> findByIds(List<String> drugIds) {
        Map<String, Drug> result = new LinkedHashMap<>();
        if (drugIds == null) return result;
        for (String id : drugIds) {
            if (id == null) continue;
            findById(id).ifPresent(d -> result.put(id, d));
        }
        return result;
    }

    List<Drug> findAll();
    List<Drug> search(String query);
    Optional<Drug> findByNameIgnoreCase(String name);
    void save(Drug drug);
    void delete(String drugId);
}
