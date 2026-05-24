package com.gte619n.healthfitness.core.medication;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the shared drug catalog.
 */
public interface DrugRepository {
    Optional<Drug> findById(String drugId);
    List<Drug> findAll();
    List<Drug> search(String query);
    Optional<Drug> findByNameIgnoreCase(String name);
    void save(Drug drug);
    void delete(String drugId);
}
