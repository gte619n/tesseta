package com.gte619n.healthfitness.core.medication;

import java.util.List;
import java.util.Optional;

/**
 * Repository for user medications.
 */
public interface MedicationRepository {
    Optional<Medication> findById(String userId, String medicationId);
    List<Medication> findByUser(String userId);
    List<Medication> findByUserAndStatus(String userId, MedicationStatus status);
    List<Medication> findByProtocol(String userId, String protocolId);
    void save(Medication medication);
    void delete(String userId, String medicationId);
    // Cross-user scan returning every medication (across all users) whose
    // drugId matches. Used by admin merge to rewrite alias references.
    List<Medication> findAllReferencingDrug(String drugId);
}
