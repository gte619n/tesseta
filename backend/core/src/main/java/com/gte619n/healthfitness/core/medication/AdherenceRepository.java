package com.gte619n.healthfitness.core.medication;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for adherence logs.
 */
public interface AdherenceRepository {
    Optional<AdherenceLog> findByDate(String userId, String medicationId, LocalDate date);
    List<AdherenceLog> findByDateRange(String userId, String medicationId, LocalDate from, LocalDate to);
    List<AdherenceLog> findByUserAndDateRange(String userId, LocalDate from, LocalDate to);
    void save(AdherenceLog log);
    void deleteByDate(String userId, String medicationId, LocalDate date);
}
