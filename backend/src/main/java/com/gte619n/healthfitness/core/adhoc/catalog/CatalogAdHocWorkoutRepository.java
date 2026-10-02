package com.gte619n.healthfitness.core.adhoc.catalog;

import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import java.util.List;
import java.util.Optional;

/** Persistence for shared ad-hoc templates at {@code adhocCatalog/{catalogId}}. */
public interface CatalogAdHocWorkoutRepository {
    Optional<CatalogAdHocWorkout> findById(String catalogId);

    List<CatalogAdHocWorkout> findByStatus(CatalogStatus status);

    List<CatalogAdHocWorkout> findAll();

    void save(CatalogAdHocWorkout workout);
}
