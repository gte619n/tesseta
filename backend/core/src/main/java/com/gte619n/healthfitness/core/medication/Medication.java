package com.gte619n.healthfitness.core.medication;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * User's medication instance.
 * Stored in: users/{userId}/medications/{medicationId}
 */
public record Medication(
    String userId,
    String medicationId,
    String drugId,                      // Reference to drugs/{drugId}
    String customName,                  // Override name if needed (nullable)
    MedicationStatus status,
    double dose,                        // 200
    String unit,                        // "mg"
    FrequencyConfig frequency,
    List<TimeSlot> timeSlots,           // [{ window: MORNING, dose: 100 }, ...]
    String protocolId,                  // Reference to protocol grouping (nullable)
    String notes,                       // (nullable)
    String prescribedBy,                // Doctor name (nullable)
    LocalDate startDate,
    LocalDate endDate,                  // Set when discontinued (nullable)
    DiscontinueReason discontinueReason, // (nullable)
    String discontinueNotes,            // (nullable)
    List<String> correlatedMarkers,     // Blood markers to show on charts
    Instant createdAt,
    Instant updatedAt
) {
    /**
     * Create a new active medication with minimal required fields.
     */
    public static Medication create(
        String userId,
        String medicationId,
        String drugId,
        double dose,
        String unit,
        FrequencyConfig frequency,
        List<TimeSlot> timeSlots,
        LocalDate startDate,
        List<String> correlatedMarkers
    ) {
        return new Medication(
            userId,
            medicationId,
            drugId,
            null,                   // customName
            MedicationStatus.ACTIVE,
            dose,
            unit,
            frequency,
            timeSlots,
            null,                   // protocolId
            null,                   // notes
            null,                   // prescribedBy
            startDate,
            null,                   // endDate
            null,                   // discontinueReason
            null,                   // discontinueNotes
            correlatedMarkers,
            Instant.now(),
            Instant.now()
        );
    }

    /**
     * Discontinue this medication.
     */
    public Medication discontinue(LocalDate endDate, DiscontinueReason reason, String notes) {
        return new Medication(
            userId,
            medicationId,
            drugId,
            customName,
            MedicationStatus.DISCONTINUED,
            dose,
            unit,
            frequency,
            timeSlots,
            protocolId,
            this.notes,
            prescribedBy,
            startDate,
            endDate,
            reason,
            notes,
            correlatedMarkers,
            createdAt,
            Instant.now()
        );
    }
}
