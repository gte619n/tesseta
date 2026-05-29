package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.core.medication.*;
import java.time.LocalDate;
import java.util.List;

/**
 * API response for a user's medication.
 */
public record MedicationResponse(
    String medicationId,
    String drugId,
    DrugResponse drug,                  // Populated drug details
    String customName,
    MedicationStatus status,
    double dose,
    String unit,
    FrequencyConfig frequency,
    List<TimeSlot> timeSlots,
    String protocolId,
    String notes,
    String prescribedBy,
    LocalDate startDate,
    LocalDate endDate,
    DiscontinueReason discontinueReason,
    String discontinueNotes,
    List<String> correlatedMarkers,
    List<DosagePeriod> dosagePeriods,   // Dated dose history (newest period is open)
    AdherenceSummary adherence          // 30-day adherence stats
) {
    public static MedicationResponse from(Medication m, Drug drug, AdherenceSummary adherence) {
        return new MedicationResponse(
            m.medicationId(),
            m.drugId(),
            drug != null ? DrugResponse.from(drug) : null,
            m.customName(),
            m.status(),
            m.dose(),
            m.unit(),
            m.frequency(),
            m.timeSlots(),
            m.protocolId(),
            m.notes(),
            m.prescribedBy(),
            m.startDate(),
            m.endDate(),
            m.discontinueReason(),
            m.discontinueNotes(),
            m.correlatedMarkers(),
            m.dosagePeriods(),
            adherence
        );
    }

    public static MedicationResponse from(Medication m, Drug drug) {
        return from(m, drug, null);
    }
}
