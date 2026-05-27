package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.medication.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for user medications.
 * Endpoints: /api/me/medications
 */
@RestController
@RequestMapping("/api/me/medications")
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class MedicationController {

    private final CurrentUserProvider currentUser;
    private final MedicationRepository medications;
    private final MedicationHistoryRepository history;
    private final AdherenceRepository adherence;
    private final DrugRepository drugs;

    public MedicationController(
        CurrentUserProvider currentUser,
        MedicationRepository medications,
        MedicationHistoryRepository history,
        AdherenceRepository adherence,
        DrugRepository drugs
    ) {
        this.currentUser = currentUser;
        this.medications = medications;
        this.history = history;
        this.adherence = adherence;
        this.drugs = drugs;
    }

    /**
     * List all user medications (optionally filter by status).
     * Includes 30-day adherence summary for each medication.
     */
    @GetMapping
    public List<MedicationResponse> list(@RequestParam(required = false) MedicationStatus status) {
        String userId = currentUser.get().userId();
        List<Medication> meds = status != null
            ? medications.findByUserAndStatus(userId, status)
            : medications.findByUser(userId);

        LocalDate today = LocalDate.now();
        LocalDate thirtyDaysAgo = today.minusDays(30);

        return meds.stream()
            .map(m -> {
                Drug drug = drugs.findById(m.drugId()).orElse(null);
                AdherenceSummary summary = calculateAdherenceSummary(
                    userId, m.medicationId(), thirtyDaysAgo, today
                );
                return MedicationResponse.from(m, drug, summary);
            })
            .toList();
    }

    /**
     * Calculate 30-day adherence summary for a medication.
     */
    private AdherenceSummary calculateAdherenceSummary(
        String userId, String medicationId, LocalDate from, LocalDate to
    ) {
        List<AdherenceLog> logs = adherence.findByDateRange(userId, medicationId, from, to);

        // Build set of dates with recorded doses
        Set<LocalDate> datesWithDoses = logs.stream()
            .filter(log -> log.doses() != null && !log.doses().isEmpty())
            .map(AdherenceLog::date)
            .collect(Collectors.toSet());

        // Build last30Days list
        List<AdherenceSummary.DayAdherence> last30Days = new ArrayList<>();
        LocalDate date = from;
        int takenCount = 0;
        int totalDays = 0;

        while (!date.isAfter(to)) {
            boolean taken = datesWithDoses.contains(date);
            last30Days.add(new AdherenceSummary.DayAdherence(date, taken));
            if (taken) takenCount++;
            totalDays++;
            date = date.plusDays(1);
        }

        double percentage = totalDays > 0 ? (takenCount * 100.0) / totalDays : 0;
        return new AdherenceSummary(last30Days, percentage);
    }

    /**
     * Get a single medication by ID.
     */
    @GetMapping("/{medicationId}")
    public ResponseEntity<MedicationDetailResponse> get(@PathVariable String medicationId) {
        String userId = currentUser.get().userId();
        return medications.findById(userId, medicationId)
            .map(m -> {
                Drug drug = drugs.findById(m.drugId()).orElse(null);
                List<MedicationHistory> hist = history.findByMedication(userId, medicationId);
                return ResponseEntity.ok(MedicationDetailResponse.from(m, drug, hist));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new medication.
     * Supports two modes:
     * 1. With drugId - links to a drug in the catalog
     * 2. With customName (no drugId) - creates a custom medication entry
     */
    @PostMapping
    public ResponseEntity<MedicationResponse> create(@RequestBody CreateMedicationRequest body) {
        validateCreateRequest(body);

        String userId = currentUser.get().userId();
        String medicationId = UUID.randomUUID().toString();

        Drug drug = null;
        final String drugIdFromRequest = body.drugId();
        final String customName = body.customName();
        String drugId;
        String unit = body.unit();
        List<String> correlatedMarkers = body.correlatedMarkers();

        if (drugIdFromRequest != null && !drugIdFromRequest.isBlank()) {
            // Mode 1: Link to existing drug in catalog
            drugId = drugIdFromRequest;
            drug = drugs.findById(drugId)
                .orElseThrow(() -> new IllegalArgumentException("Drug not found: " + drugIdFromRequest));
            if (unit == null) unit = drug.defaultUnit();
            if (correlatedMarkers == null) correlatedMarkers = drug.suggestedMarkers();
        } else if (customName != null && !customName.isBlank()) {
            // Mode 2: Custom medication entry
            // No drugId needed - store customName directly
            drugId = null;
            if (unit == null) unit = "mg";
            if (correlatedMarkers == null) correlatedMarkers = List.of();
        } else {
            throw new IllegalArgumentException("Either drugId or customName is required");
        }

        Medication medication = new Medication(
            userId,
            medicationId,
            drugId,
            customName,
            MedicationStatus.ACTIVE,
            body.dose(),
            unit,
            body.frequency(),
            body.timeSlots() != null ? body.timeSlots() : List.of(),
            body.protocolId(),
            body.notes(),
            body.prescribedBy(),
            body.startDate() != null ? body.startDate() : LocalDate.now(),
            null,   // endDate
            null,   // discontinueReason
            null,   // discontinueNotes
            correlatedMarkers,
            Instant.now(),
            Instant.now()
        );

        medications.save(medication);
        return ResponseEntity.status(201).body(MedicationResponse.from(medication, drug));
    }

    /**
     * Update a medication.
     * If dose changes, creates a history entry.
     */
    @PutMapping("/{medicationId}")
    public ResponseEntity<MedicationResponse> update(
        @PathVariable String medicationId,
        @RequestBody UpdateMedicationRequest body
    ) {
        String userId = currentUser.get().userId();

        Medication existing = medications.findById(userId, medicationId)
            .orElseThrow(() -> new IllegalArgumentException("Medication not found"));

        // Track dose change if applicable
        if (body.dose() != null && body.dose() != existing.dose()) {
            String historyId = UUID.randomUUID().toString();
            MedicationHistory change = new MedicationHistory(
                historyId,
                userId,
                medicationId,
                ChangeType.DOSE_CHANGE,
                String.valueOf(existing.dose()) + " " + existing.unit(),
                String.valueOf(body.dose()) + " " + (body.unit() != null ? body.unit() : existing.unit()),
                Instant.now(),
                body.changeNotes()
            );
            history.save(change);
        }

        // Track frequency change
        if (body.frequency() != null && !body.frequency().equals(existing.frequency())) {
            String historyId = UUID.randomUUID().toString();
            MedicationHistory change = new MedicationHistory(
                historyId,
                userId,
                medicationId,
                ChangeType.FREQUENCY_CHANGE,
                existing.frequency().type().name(),
                body.frequency().type().name(),
                Instant.now(),
                body.changeNotes()
            );
            history.save(change);
        }

        Medication updated = new Medication(
            userId,
            medicationId,
            existing.drugId(),
            body.customName() != null ? body.customName() : existing.customName(),
            existing.status(),
            body.dose() != null ? body.dose() : existing.dose(),
            body.unit() != null ? body.unit() : existing.unit(),
            body.frequency() != null ? body.frequency() : existing.frequency(),
            body.timeSlots() != null ? body.timeSlots() : existing.timeSlots(),
            body.protocolId() != null ? body.protocolId() : existing.protocolId(),
            body.notes() != null ? body.notes() : existing.notes(),
            body.prescribedBy() != null ? body.prescribedBy() : existing.prescribedBy(),
            existing.startDate(),
            existing.endDate(),
            existing.discontinueReason(),
            existing.discontinueNotes(),
            body.correlatedMarkers() != null ? body.correlatedMarkers() : existing.correlatedMarkers(),
            existing.createdAt(),
            Instant.now()
        );

        medications.save(updated);
        Drug drug = drugs.findById(existing.drugId()).orElse(null);
        return ResponseEntity.ok(MedicationResponse.from(updated, drug));
    }

    /**
     * Discontinue a medication.
     */
    @PostMapping("/{medicationId}/discontinue")
    public ResponseEntity<MedicationResponse> discontinue(
        @PathVariable String medicationId,
        @RequestBody DiscontinueRequest body
    ) {
        String userId = currentUser.get().userId();

        Medication existing = medications.findById(userId, medicationId)
            .orElseThrow(() -> new IllegalArgumentException("Medication not found"));

        if (existing.status() == MedicationStatus.DISCONTINUED) {
            throw new IllegalStateException("Medication is already discontinued");
        }

        LocalDate endDate = body.endDate() != null ? body.endDate() : LocalDate.now();

        Medication discontinued = existing.discontinue(endDate, body.reason(), body.notes());
        medications.save(discontinued);

        Drug drug = drugs.findById(existing.drugId()).orElse(null);
        return ResponseEntity.ok(MedicationResponse.from(discontinued, drug));
    }

    /**
     * Delete a medication (hard delete).
     */
    @DeleteMapping("/{medicationId}")
    public ResponseEntity<Void> delete(@PathVariable String medicationId) {
        String userId = currentUser.get().userId();
        medications.delete(userId, medicationId);
        return ResponseEntity.noContent().build();
    }

    private void validateCreateRequest(CreateMedicationRequest body) {
        boolean hasDrugId = body.drugId() != null && !body.drugId().isBlank();
        boolean hasCustomName = body.customName() != null && !body.customName().isBlank();

        if (!hasDrugId && !hasCustomName) {
            throw new IllegalArgumentException("Either drugId or customName is required");
        }
        if (body.dose() == null || body.dose() <= 0) {
            throw new IllegalArgumentException("dose is required and must be positive");
        }
        if (body.frequency() == null) {
            throw new IllegalArgumentException("frequency is required");
        }
    }

    // Request DTOs as inner records

    public record CreateMedicationRequest(
        String drugId,
        String customName,
        Double dose,
        String unit,
        FrequencyConfig frequency,
        List<TimeSlot> timeSlots,
        String protocolId,
        String notes,
        String prescribedBy,
        LocalDate startDate,
        List<String> correlatedMarkers
    ) {}

    public record UpdateMedicationRequest(
        String customName,
        Double dose,
        String unit,
        FrequencyConfig frequency,
        List<TimeSlot> timeSlots,
        String protocolId,
        String notes,
        String prescribedBy,
        List<String> correlatedMarkers,
        String changeNotes           // Notes for the history entry
    ) {}

    public record DiscontinueRequest(
        DiscontinueReason reason,
        String notes,
        LocalDate endDate
    ) {}
}
