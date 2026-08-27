package com.gte619n.healthfitness.core.medication;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// Computes a user's scheduled doses for a given local date, with taken status
// (ADR-0020, decision D10). Extracted from TodaysDosesController so the
// first-party "today's doses" endpoint and the third-party GET /v1/doses share
// one source of truth for the frequency / day-of-week / cycle scheduling rule
// rather than duplicating it.
//
// "Today" is the caller's local date: a phone behind/ahead of the server sees
// its own calendar day and the checklist resets at local midnight. Doses are
// logged against the same client-local date, so the two stay consistent.
@Service
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class TodaysDosesService {

    private final MedicationRepository medications;
    private final AdherenceRepository adherence;
    private final DrugRepository drugs;

    public TodaysDosesService(
        MedicationRepository medications,
        AdherenceRepository adherence,
        DrugRepository drugs
    ) {
        this.medications = medications;
        this.adherence = adherence;
        this.drugs = drugs;
    }

    public List<TodaysDose> forDate(String userId, LocalDate today) {
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        List<Medication> activeMeds = medications.findByUserAndStatus(userId, MedicationStatus.ACTIVE);
        List<AdherenceLog> todayLogs = adherence.findByUserAndDateRange(userId, today, today);

        // IMPL-21: a dose counts as taken only when its log is NOT a missed marker.
        Set<String> taken = todayLogs.stream()
            .flatMap(log -> log.doses().stream()
                .filter(dose -> !dose.missed())
                .map(dose -> log.medicationId() + ":" + dose.window().name()))
            .collect(Collectors.toSet());

        Map<String, Drug> drugsById = drugs.findByIds(activeMeds.stream()
            .map(Medication::drugId)
            .filter(id -> id != null)
            .distinct()
            .toList());

        List<TodaysDose> doses = new ArrayList<>();
        for (Medication med : activeMeds) {
            if (med.frequency().type() == FrequencyType.PRN) {
                continue; // as-needed, not scheduled
            }
            if (!isScheduledForToday(med.frequency(), dayOfWeek, today)) {
                continue;
            }

            Drug drug = med.drugId() != null ? drugsById.get(med.drugId()) : null;
            String drugName = med.customName() != null ? med.customName()
                : (drug != null ? drug.name() : "Unknown");
            String imageUrl = drug != null ? drug.imageUrl() : null;

            List<TimeSlot> timeSlots = med.timeSlots();
            if (timeSlots == null || timeSlots.isEmpty()) {
                timeSlots = List.of(new TimeSlot(TimeWindow.MORNING, med.dose()));
            }

            AdherenceLog todayLog = todayLogs.stream()
                .filter(log -> log.medicationId().equals(med.medicationId()))
                .findFirst()
                .orElse(null);

            for (TimeSlot slot : timeSlots) {
                boolean isTaken = taken.contains(med.medicationId() + ":" + slot.window().name());
                Instant takenAt = null;
                if (isTaken && todayLog != null) {
                    takenAt = todayLog.doses().stream()
                        .filter(d -> d.window() == slot.window())
                        .map(DoseLog::takenAt)
                        .findFirst()
                        .orElse(null);
                }
                doses.add(new TodaysDose(
                    med.medicationId(), drugName, imageUrl, slot.window(),
                    slot.dose(), med.unit(), isTaken, takenAt));
            }
        }

        doses.sort(Comparator.comparingInt(d -> windowOrder(d.window())));
        return doses;
    }

    private static boolean isScheduledForToday(FrequencyConfig freq, DayOfWeek dayOfWeek, LocalDate today) {
        return switch (freq.type()) {
            case DAILY -> true;
            case WEEKLY -> {
                if (freq.specificDays() != null && !freq.specificDays().isEmpty()) {
                    yield freq.specificDays().stream().anyMatch(d -> toDayOfWeek(d) == dayOfWeek);
                }
                yield true;
            }
            case MONTHLY -> true;
            case CYCLE -> {
                if (freq.cycle() != null) {
                    LocalDate startDate = freq.cycle().startDate();
                    long daysSinceStart = ChronoUnit.DAYS.between(startDate, today);
                    int cycleLength = (freq.cycle().onWeeks() + freq.cycle().offWeeks()) * 7;
                    long dayInCycle = daysSinceStart % cycleLength;
                    yield dayInCycle < freq.cycle().onWeeks() * 7L;
                }
                yield true;
            }
            case PRN -> false;
        };
    }

    private static DayOfWeek toDayOfWeek(com.gte619n.healthfitness.core.medication.DayOfWeek day) {
        return switch (day) {
            case MON -> DayOfWeek.MONDAY;
            case TUE -> DayOfWeek.TUESDAY;
            case WED -> DayOfWeek.WEDNESDAY;
            case THU -> DayOfWeek.THURSDAY;
            case FRI -> DayOfWeek.FRIDAY;
            case SAT -> DayOfWeek.SATURDAY;
            case SUN -> DayOfWeek.SUNDAY;
        };
    }

    private static int windowOrder(TimeWindow window) {
        return switch (window) {
            case MORNING -> 0;
            case AFTERNOON -> 1;
            case EVENING -> 2;
            case BEDTIME -> 3;
        };
    }
}
