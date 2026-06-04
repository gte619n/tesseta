package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.api.sync.WriteResult;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.medication.*;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for medication adherence tracking.
 * Endpoints: /api/me/medications/{id}/adherence
 */
@RestController
@RequestMapping("/api/me/medications/{medicationId}/adherence")
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class AdherenceController {

    private final CurrentUserProvider currentUser;
    private final MedicationRepository medications;
    private final AdherenceRepository adherence;
    private final DrugRepository drugs;
    private final MetricChangedPublisher metricChangedPublisher;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public AdherenceController(
        CurrentUserProvider currentUser,
        MedicationRepository medications,
        AdherenceRepository adherence,
        DrugRepository drugs,
        MetricChangedPublisher metricChangedPublisher,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.medications = medications;
        this.adherence = adherence;
        this.drugs = drugs;
        this.metricChangedPublisher = metricChangedPublisher;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    /**
     * Get adherence logs for a date range.
     */
    @GetMapping
    public List<AdherenceLogResponse> list(
        @PathVariable String medicationId,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        String userId = currentUser.get().userId();

        // Verify medication exists
        medications.findById(userId, medicationId)
            .orElseThrow(() -> new IllegalArgumentException("Medication not found"));

        // Default to last 30 days
        LocalDate endDate = to != null ? to : LocalDate.now();
        LocalDate startDate = from != null ? from : endDate.minusDays(30);

        return adherence.findByDateRange(userId, medicationId, startDate, endDate)
            .stream()
            .map(AdherenceLogResponse::from)
            .toList();
    }

    /**
     * Log a dose taken.
     */
    @PostMapping
    public ResponseEntity<WriteResult<AdherenceLogResponse>> logDose(
        @PathVariable String medicationId,
        @RequestBody LogDoseRequest body
    ) {
        String userId = currentUser.get().userId();

        // Verify medication exists
        Medication med = medications.findById(userId, medicationId)
            .orElseThrow(() -> new IllegalArgumentException("Medication not found"));

        LocalDate date = body.date() != null ? body.date() : LocalDate.now();
        TimeWindow window = body.window();
        double dose = body.dose() != null ? body.dose() : med.dose();

        // Adherence is an upsert keyed by (medication, date) — not a generated
        // id — so the offline contract here is: an Idempotency-Key replay is a
        // no-op that returns the current state of the day's log, and the write
        // fans out (origin suppressed) carrying an authoritative lastUpdate (#11,
        // D7/D18). The replay scope is per (medication, date).
        WriteResult<AdherenceLogResponse> response = syncWrite.idempotentCreate(
            "medicationAdherence:log:" + medicationId + ":" + date,
            userId,
            () -> {
                AdherenceLog existing = adherence.findByDate(userId, medicationId, date)
                    .orElse(null);

                List<DoseLog> doses = existing != null
                    ? new ArrayList<>(existing.doses())
                    : new ArrayList<>();

                Instant writtenAt = Instant.now();
                DoseLog newDose = new DoseLog(window, writtenAt, dose);
                doses.removeIf(d -> d.window() == window);  // Remove existing for this window
                doses.add(newDose);

                AdherenceLog log = new AdherenceLog(userId, medicationId, date, doses, body.notes());

                adherence.save(log);
                metricChangedPublisher.publish(userId, MetricKey.MEDS_ADHERENCE_30D);
                syncNotifier.changed(userId, syncWrite.originDeviceId(), "medicationAdherence");
                return new SyncWriteContext.Created<>(
                    medicationId + ":" + date,
                    WriteResult.of(AdherenceLogResponse.from(log), writtenAt));
            },
            id -> adherence.findByDate(userId, medicationId, date)
                .map(log -> WriteResult.of(AdherenceLogResponse.from(log), Instant.now()))
        );
        return ResponseEntity.status(201).body(response);
    }

    /**
     * Undo a dose log for a specific date and window.
     */
    @DeleteMapping("/{date}/{window}")
    public ResponseEntity<Void> undoDose(
        @PathVariable String medicationId,
        @PathVariable LocalDate date,
        @PathVariable TimeWindow window
    ) {
        String userId = currentUser.get().userId();

        // Verify medication exists
        medications.findById(userId, medicationId)
            .orElseThrow(() -> new IllegalArgumentException("Medication not found"));

        // Get existing log
        AdherenceLog existing = adherence.findByDate(userId, medicationId, date)
            .orElse(null);

        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        // Remove the dose for this window
        List<DoseLog> doses = new ArrayList<>(existing.doses());
        doses.removeIf(d -> d.window() == window);

        if (doses.isEmpty()) {
            // No more doses, delete the whole log
            adherence.deleteByDate(userId, medicationId, date);
        } else {
            // Save updated log
            AdherenceLog updated = new AdherenceLog(
                userId,
                medicationId,
                date,
                doses,
                existing.notes()
            );
            adherence.save(updated);
        }
        // Publish after whichever write path ran — 30-day rolling metric changed.
        metricChangedPublisher.publish(userId, MetricKey.MEDS_ADHERENCE_30D);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "medicationAdherence");

        return ResponseEntity.noContent().build();
    }

    // Request/Response DTOs

    public record LogDoseRequest(
        LocalDate date,
        TimeWindow window,
        Double dose,
        String notes
    ) {}

    public record AdherenceLogResponse(
        LocalDate date,
        List<DoseLogResponse> doses,
        String notes
    ) {
        public static AdherenceLogResponse from(AdherenceLog log) {
            return new AdherenceLogResponse(
                log.date(),
                log.doses() != null
                    ? log.doses().stream().map(DoseLogResponse::from).toList()
                    : List.of(),
                log.notes()
            );
        }
    }

    public record DoseLogResponse(
        TimeWindow window,
        Instant takenAt,
        double dose
    ) {
        public static DoseLogResponse from(DoseLog d) {
            return new DoseLogResponse(d.window(), d.takenAt(), d.dose());
        }
    }
}
