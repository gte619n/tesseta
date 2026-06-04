package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.core.medication.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Detailed API response for a single medication, including change history.
 */
public record MedicationDetailResponse(
    String medicationId,
    String drugId,
    DrugResponse drug,
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
    List<HistoryEntry> history
) {
    public static MedicationDetailResponse from(Medication m, Drug drug, List<MedicationHistory> history) {
        return new MedicationDetailResponse(
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
            history != null ? history.stream().map(HistoryEntry::from).toList() : List.of()
        );
    }

    public record HistoryEntry(
        String historyId,
        ChangeType changeType,
        String previousValue,
        String newValue,
        Instant changedAt,
        String notes
    ) {
        public static HistoryEntry from(MedicationHistory h) {
            return new HistoryEntry(
                h.historyId(),
                h.changeType(),
                h.previousValue(),
                h.newValue(),
                h.changedAt(),
                h.notes()
            );
        }
    }
}
