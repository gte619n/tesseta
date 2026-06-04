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
    List<DosagePeriod> dosagePeriods,   // Dated dose history; active period has endDate==null
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
            List.of(DosagePeriod.initial(dose, unit, startDate)),
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
            dosagePeriods,
            createdAt,
            Instant.now()
        );
    }

    /** Return a copy with a different dosage-period list. */
    public Medication withDosagePeriods(List<DosagePeriod> newPeriods) {
        return new Medication(
            userId, medicationId, drugId, customName, status, dose, unit, frequency, timeSlots,
            protocolId, notes, prescribedBy, startDate, endDate, discontinueReason, discontinueNotes,
            correlatedMarkers, newPeriods, createdAt, Instant.now()
        );
    }

    /**
     * Reactivate a discontinued medication. Clears the end date and discontinue
     * reason/notes and reopens the most recent dosage period from {@code resumeDate}.
     */
    public Medication reactivate(LocalDate resumeDate, List<DosagePeriod> reopenedPeriods) {
        return new Medication(
            userId,
            medicationId,
            drugId,
            customName,
            MedicationStatus.ACTIVE,
            dose,
            unit,
            frequency,
            timeSlots,
            protocolId,
            notes,
            prescribedBy,
            startDate,
            null,                   // endDate
            null,                   // discontinueReason
            null,                   // discontinueNotes
            correlatedMarkers,
            reopenedPeriods,
            createdAt,
            Instant.now()
        );
    }
}
