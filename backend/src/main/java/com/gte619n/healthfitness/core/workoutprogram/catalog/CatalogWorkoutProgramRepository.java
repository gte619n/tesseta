package com.gte619n.healthfitness.core.workoutprogram.catalog;

import com.gte619n.healthfitness.core.catalog.CatalogStatus;
import java.util.List;
import java.util.Optional;

/** Persistence for shared program templates at {@code programCatalog/{catalogId}}. */
public interface CatalogWorkoutProgramRepository {
    Optional<CatalogWorkoutProgram> findById(String catalogId);

    /** All catalog rows in the given status (unfiltered by alias). */
    List<CatalogWorkoutProgram> findByStatus(CatalogStatus status);

    /** Every catalog row regardless of status (admin/aggregate reads). */
    List<CatalogWorkoutProgram> findAll();

    void save(CatalogWorkoutProgram program);
}
