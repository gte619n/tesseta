package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.medication.*;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for today's scheduled doses.
 * Endpoint: /api/me/medications/today
 */
@RestController
@RequestMapping("/api/me/medications/today")
public class TodaysDosesController {

    private final CurrentUserProvider currentUser;
    private final MedicationRepository medications;
    private final AdherenceRepository adherence;
    private final DrugRepository drugs;

    public TodaysDosesController(
        CurrentUserProvider currentUser,
        MedicationRepository medications,
        AdherenceRepository adherence,
        DrugRepository drugs
    ) {
        this.currentUser = currentUser;
        this.medications = medications;
        this.adherence = adherence;
        this.drugs = drugs;
    }

    /**
     * Get all scheduled doses for today with their taken status.
     */
    @GetMapping
    public List<TodaysDoseResponse> list() {
        String userId = currentUser.get().userId();
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        // Get all active medications
        List<Medication> activeMeds = medications.findByUserAndStatus(userId, MedicationStatus.ACTIVE);

        // Get today's adherence logs
        List<AdherenceLog> todayLogs = adherence.findByUserAndDateRange(userId, today, today);

        // Build set of already-taken (medicationId:window)
        Set<String> taken = todayLogs.stream()
            .flatMap(log -> log.doses().stream()
                .map(dose -> log.medicationId() + ":" + dose.window().name()))
            .collect(Collectors.toSet());

        // Build response for each scheduled dose
        List<TodaysDoseResponse> doses = new ArrayList<>();

        for (Medication med : activeMeds) {
            // Skip PRN medications (as needed, not scheduled)
            if (med.frequency().type() == FrequencyType.PRN) {
                continue;
            }

            // Check if medication is scheduled for today
            if (!isScheduledForToday(med.frequency(), dayOfWeek, today)) {
                continue;
            }

            // Get drug info
            Drug drug = drugs.findById(med.drugId()).orElse(null);
            String drugName = med.customName() != null ? med.customName()
                : (drug != null ? drug.name() : "Unknown");
            String imageUrl = drug != null ? drug.imageUrl() : null;

            // Get time slots (or default to a single dose)
            List<TimeSlot> timeSlots = med.timeSlots();
            if (timeSlots == null || timeSlots.isEmpty()) {
                // Default: single morning dose
                timeSlots = List.of(new TimeSlot(TimeWindow.MORNING, med.dose()));
            }

            // Find taken info from adherence logs
            AdherenceLog todayLog = todayLogs.stream()
                .filter(log -> log.medicationId().equals(med.medicationId()))
                .findFirst()
                .orElse(null);

            for (TimeSlot slot : timeSlots) {
                String key = med.medicationId() + ":" + slot.window().name();
                boolean isTaken = taken.contains(key);
                Instant takenAt = null;

                if (isTaken && todayLog != null) {
                    takenAt = todayLog.doses().stream()
                        .filter(d -> d.window() == slot.window())
                        .map(DoseLog::takenAt)
                        .findFirst()
                        .orElse(null);
                }

                doses.add(new TodaysDoseResponse(
                    med.medicationId(),
                    drugName,
                    imageUrl,
                    slot.window(),
                    slot.dose(),
                    med.unit(),
                    isTaken,
                    takenAt
                ));
            }
        }

        // Sort by time window order
        doses.sort((a, b) -> {
            int orderA = getWindowOrder(a.window());
            int orderB = getWindowOrder(b.window());
            return Integer.compare(orderA, orderB);
        });

        return doses;
    }

    /**
     * Check if medication is scheduled for today based on frequency config.
     */
    private boolean isScheduledForToday(FrequencyConfig freq, DayOfWeek dayOfWeek, LocalDate today) {
        return switch (freq.type()) {
            case DAILY -> true;  // Always scheduled
            case WEEKLY -> {
                if (freq.specificDays() != null && !freq.specificDays().isEmpty()) {
                    // Check if today matches specific days
                    yield freq.specificDays().stream()
                        .anyMatch(d -> toDayOfWeek(d) == dayOfWeek);
                }
                // Default: scheduled every day
                yield true;
            }
            case MONTHLY -> {
                // For now, assume scheduled on specific day of month
                // Could be improved with more sophisticated logic
                yield true;
            }
            case CYCLE -> {
                if (freq.cycle() != null) {
                    // Calculate if we're in an "on" period
                    LocalDate startDate = freq.cycle().startDate();
                    long daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(startDate, today);
                    int cycleLength = (freq.cycle().onWeeks() + freq.cycle().offWeeks()) * 7;
                    long dayInCycle = daysSinceStart % cycleLength;
                    yield dayInCycle < freq.cycle().onWeeks() * 7;
                }
                yield true;
            }
            case PRN -> false;  // Never scheduled (as needed)
        };
    }

    /**
     * Convert our DayOfWeek enum to Java's DayOfWeek.
     */
    private DayOfWeek toDayOfWeek(com.gte619n.healthfitness.core.medication.DayOfWeek day) {
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

    /**
     * Get sort order for time window.
     */
    private int getWindowOrder(TimeWindow window) {
        return switch (window) {
            case MORNING -> 0;
            case AFTERNOON -> 1;
            case EVENING -> 2;
            case BEDTIME -> 3;
        };
    }

    // Response DTO

    public record TodaysDoseResponse(
        String medicationId,
        String drugName,
        String imageUrl,
        TimeWindow window,
        double dose,
        String unit,
        boolean taken,
        Instant takenAt
    ) {}
}
