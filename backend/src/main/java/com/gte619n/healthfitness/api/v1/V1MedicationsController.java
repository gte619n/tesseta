package com.gte619n.healthfitness.api.v1;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.medication.AdherenceLog;
import com.gte619n.healthfitness.core.medication.AdherenceRepository;
import com.gte619n.healthfitness.core.medication.CycleConfig;
import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import com.gte619n.healthfitness.core.medication.FrequencyConfig;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.MedicationStatus;
import com.gte619n.healthfitness.core.medication.TimeSlot;
import com.gte619n.healthfitness.core.medication.TodaysDosesService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The medications + adherence surface (ADR-0020) — the highest-value monitoring
// signal. Requires medications:read. Gated on the medications feature flag (its
// TodaysDosesService dependency is), which is on wherever the platform runs.
//   GET /v1/medications?status=       — active/discontinued medications
//   GET /v1/medications/{id}          — one medication
//   GET /v1/doses?date=               — today's scheduled doses + taken status
//   GET /v1/adherence?from=&to=       — adherence logs across all medications
@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAuthority('SCOPE_medications:read')")
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class V1MedicationsController {

    private static final int MAX_ADHERENCE_DAYS = 366;

    private final CurrentUserProvider currentUser;
    private final MedicationRepository medications;
    private final DrugRepository drugs;
    private final AdherenceRepository adherence;
    private final TodaysDosesService todaysDoses;

    public V1MedicationsController(
        CurrentUserProvider currentUser,
        MedicationRepository medications,
        DrugRepository drugs,
        AdherenceRepository adherence,
        TodaysDosesService todaysDoses
    ) {
        this.currentUser = currentUser;
        this.medications = medications;
        this.drugs = drugs;
        this.adherence = adherence;
        this.todaysDoses = todaysDoses;
    }

    @GetMapping("/medications")
    public V1Page<MedicationDto> listMedications(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String updatedSince
    ) {
        String userId = currentUser.get().userId();
        Instant since = V1Params.instant(updatedSince);
        List<Medication> meds = status == null || status.isBlank()
            ? medications.findByUser(userId)
            : medications.findByUserAndStatus(userId, parseStatus(status));
        List<Medication> filtered = meds.stream()
            .filter(m -> since == null || (m.updatedAt() != null && !m.updatedAt().isBefore(since)))
            .toList();
        Map<String, Drug> drugsById = drugs.findByIds(filtered.stream()
            .map(Medication::drugId).filter(id -> id != null).distinct().toList());
        return V1Page.paginate(filtered, Medication::updatedAt, Medication::medicationId,
            m -> toMedication(m, drugsById), cursor, V1Params.limit(limit));
    }

    @GetMapping("/medications/{medicationId}")
    public MedicationDto getMedication(@PathVariable String medicationId) {
        String userId = currentUser.get().userId();
        Medication med = medications.findById(userId, medicationId)
            .orElseThrow(() -> new NoSuchElementException("medication not found"));
        Map<String, Drug> drugsById = med.drugId() == null ? Map.of()
            : drugs.findByIds(List.of(med.drugId()));
        return toMedication(med, drugsById);
    }

    @GetMapping("/doses")
    public List<DoseDto> doses(@RequestParam(required = false) String date) {
        String userId = currentUser.get().userId();
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : V1Params.date(date);
        return todaysDoses.forDate(userId, day).stream()
            .map(d -> new DoseDto(d.medicationId(), d.drugName(), d.imageUrl(),
                d.window().name(), d.dose(), d.unit(), d.taken(), d.takenAt()))
            .toList();
    }

    @GetMapping("/adherence")
    public V1Page<AdherenceDto> adherence(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    ) {
        String userId = currentUser.get().userId();
        V1Params.DateRange range = V1Params.dateRange(from, to, 30, MAX_ADHERENCE_DAYS);
        List<AdherenceLog> logs = adherence.findByUserAndDateRange(userId, range.from(), range.to());
        return V1Page.paginate(logs,
            log -> log.date() == null ? Instant.EPOCH
                : log.date().atStartOfDay(ZoneOffset.UTC).toInstant(),
            log -> log.date() + "_" + log.medicationId(),
            V1MedicationsController::toAdherence, cursor, V1Params.limit(limit));
    }

    private static MedicationStatus parseStatus(String status) {
        try {
            return MedicationStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown status '" + status
                + "' (expected ACTIVE or DISCONTINUED)");
        }
    }

    private static MedicationDto toMedication(Medication m, Map<String, Drug> drugsById) {
        Drug drug = m.drugId() == null ? null : drugsById.get(m.drugId());
        String name = m.customName() != null ? m.customName()
            : (drug != null ? drug.name() : null);
        List<TimeSlotDto> slots = m.timeSlots() == null ? List.of()
            : m.timeSlots().stream().map(V1MedicationsController::toTimeSlot).toList();
        return new MedicationDto(
            m.medicationId(), m.drugId(), name, m.status() == null ? null : m.status().name(),
            m.dose(), m.unit(), toFrequency(m.frequency()), slots,
            m.startDate(), m.endDate(), m.createdAt(), m.updatedAt());
    }

    private static FrequencyDto toFrequency(FrequencyConfig f) {
        if (f == null) return null;
        List<String> days = f.specificDays() == null ? null
            : f.specificDays().stream().map(Enum::name).toList();
        CycleConfig c = f.cycle();
        CycleDto cycle = c == null ? null : new CycleDto(c.onWeeks(), c.offWeeks(), c.startDate());
        return new FrequencyDto(f.type() == null ? null : f.type().name(),
            f.timesPerPeriod(), days, cycle);
    }

    private static TimeSlotDto toTimeSlot(TimeSlot s) {
        return new TimeSlotDto(s.window() == null ? null : s.window().name(), s.dose());
    }

    private static AdherenceDto toAdherence(AdherenceLog log) {
        List<DoseLogDto> doses = log.doses() == null ? List.of()
            : log.doses().stream()
                .map(d -> new DoseLogDto(d.window() == null ? null : d.window().name(),
                    d.takenAt(), d.dose()))
                .toList();
        return new AdherenceDto(log.medicationId(), log.date(), doses, log.notes());
    }

    public record MedicationDto(
        String id, String drugId, String name, String status, double dose, String unit,
        FrequencyDto frequency, List<TimeSlotDto> timeSlots, LocalDate startDate,
        LocalDate endDate, Instant createdAt, Instant updatedAt) {}

    public record FrequencyDto(
        String type, Integer timesPerPeriod, List<String> specificDays, CycleDto cycle) {}

    public record CycleDto(int onWeeks, int offWeeks, LocalDate startDate) {}

    public record TimeSlotDto(String window, double dose) {}

    public record DoseDto(
        String medicationId, String drugName, String imageUrl, String window,
        double dose, String unit, boolean taken, Instant takenAt) {}

    public record AdherenceDto(
        String medicationId, LocalDate date, List<DoseLogDto> doses, String notes) {}

    public record DoseLogDto(String window, Instant takenAt, double dose) {}
}
