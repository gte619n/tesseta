package com.gte619n.healthfitness.core.medication;

import java.util.List;

/**
 * Repository for medication change history.
 */
public interface MedicationHistoryRepository {
    List<MedicationHistory> findByMedication(String userId, String medicationId);
    void save(MedicationHistory history);
}
