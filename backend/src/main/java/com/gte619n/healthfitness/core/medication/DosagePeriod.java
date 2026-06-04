package com.gte619n.healthfitness.core.medication;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A dated dosage period for a medication.
 *
 * <p>Models the history of how a medication's dose changed over time, e.g.
 * "25 mg from 2026-01-26 to 2026-05-26, then 50 mg from 2026-05-26 onward".
 *
 * <p>The <em>active</em> (current) period is the one with {@code endDate == null}.
 * End dates are <strong>exclusive</strong>: a closed period's {@code endDate}
 * equals the {@code startDate} of the period that follows it. Periods must not
 * overlap, but gaps are permitted (a medication can be paused and resumed).
 */
public record DosagePeriod(
    double dose,
    String unit,
    LocalDate startDate,
    LocalDate endDate   // nullable; null == active/current period
) {
    /** True if this is the open-ended, currently-active period. */
    public boolean isActive() {
        return endDate == null;
    }

    /** Seed a single open-ended period (used on create and for legacy migration). */
    public static DosagePeriod initial(double dose, String unit, LocalDate startDate) {
        return new DosagePeriod(dose, unit, startDate, null);
    }

    /** The current/active period in the list, or null if there is none. */
    public static DosagePeriod active(List<DosagePeriod> periods) {
        if (periods == null) return null;
        return periods.stream().filter(DosagePeriod::isActive).findFirst().orElse(null);
    }

    /**
     * Close the active period at {@code effective} and open a new period at the
     * same date with the new dose/unit. Returns a new list (input is not mutated).
     */
    public static List<DosagePeriod> changeDose(
        List<DosagePeriod> periods, double dose, String unit, LocalDate effective
    ) {
        List<DosagePeriod> result = new ArrayList<>();
        for (DosagePeriod p : periods) {
            if (p.isActive()) {
                result.add(new DosagePeriod(p.dose(), p.unit(), p.startDate(), effective));
            } else {
                result.add(p);
            }
        }
        result.add(new DosagePeriod(dose, unit, effective, null));
        return List.copyOf(result);
    }

    /**
     * Close the active (open) period at {@code endDate}, leaving no open period.
     * Used when a medication is discontinued so the dose history shows the final
     * period ending. No-op if there is no open period.
     */
    public static List<DosagePeriod> closeActive(List<DosagePeriod> periods, LocalDate endDate) {
        if (periods == null) return List.of();
        return periods.stream()
            .map(p -> p.isActive() ? new DosagePeriod(p.dose(), p.unit(), p.startDate(), endDate) : p)
            .toList();
    }

    /**
     * Reopen dosing from {@code resumeDate} by appending a new open period with the
     * given dose/unit. Used when a discontinued medication is reactivated; the gap
     * between the previous period's end and {@code resumeDate} reflects the pause.
     */
    public static List<DosagePeriod> reopen(
        List<DosagePeriod> periods, double dose, String unit, LocalDate resumeDate
    ) {
        List<DosagePeriod> result = new ArrayList<>(periods == null ? List.of() : periods);
        // Close any still-open period first so there is exactly one open period.
        result = result.stream()
            .map(p -> p.isActive() ? new DosagePeriod(p.dose(), p.unit(), p.startDate(), resumeDate) : p)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        result.add(new DosagePeriod(dose, unit, resumeDate, null));
        return List.copyOf(result);
    }

    /**
     * Shift the earliest period's start date to {@code newStart}, keeping all other
     * periods unchanged. Used when the medication's start date is edited.
     */
    public static List<DosagePeriod> shiftEarliestStart(List<DosagePeriod> periods, LocalDate newStart) {
        if (periods == null || periods.isEmpty()) return periods;
        DosagePeriod earliest = periods.stream()
            .min(Comparator.comparing(DosagePeriod::startDate))
            .orElseThrow();
        return periods.stream()
            .map(p -> p == earliest
                ? new DosagePeriod(p.dose(), p.unit(), newStart, p.endDate())
                : p)
            .toList();
    }

    /**
     * Replace the active period's dose/unit in place (keeping its start date),
     * without opening a new period. Used to keep the denormalized dose/unit on
     * the medication in sync when edited via the legacy update path.
     */
    public static List<DosagePeriod> replaceActive(
        List<DosagePeriod> periods, double dose, String unit
    ) {
        return periods.stream()
            .map(p -> p.isActive() ? new DosagePeriod(dose, unit, p.startDate(), null) : p)
            .toList();
    }

    /**
     * Validate ordering, non-overlap, and that exactly one open period exists and
     * is the most recent. Throws {@link IllegalArgumentException} on any violation.
     */
    public static void validate(List<DosagePeriod> periods) {
        if (periods == null || periods.isEmpty()) {
            throw new IllegalArgumentException("dosagePeriods must not be empty");
        }
        List<DosagePeriod> sorted = periods.stream()
            .sorted(Comparator.comparing(DosagePeriod::startDate))
            .toList();

        long openCount = sorted.stream().filter(DosagePeriod::isActive).count();
        if (openCount != 1) {
            throw new IllegalArgumentException(
                "Exactly one open dosage period (no endDate) is required, found " + openCount);
        }
        if (!sorted.get(sorted.size() - 1).isActive()) {
            throw new IllegalArgumentException("The most recent dosage period must be the open one");
        }

        for (DosagePeriod p : sorted) {
            if (p.dose() <= 0) {
                throw new IllegalArgumentException("dose must be positive");
            }
            if (p.unit() == null || p.unit().isBlank()) {
                throw new IllegalArgumentException("unit is required for each dosage period");
            }
            if (p.startDate() == null) {
                throw new IllegalArgumentException("startDate is required for each dosage period");
            }
            if (p.endDate() != null && !p.endDate().isAfter(p.startDate())) {
                throw new IllegalArgumentException("endDate must be after startDate");
            }
        }

        for (int i = 0; i < sorted.size() - 1; i++) {
            DosagePeriod cur = sorted.get(i);
            DosagePeriod next = sorted.get(i + 1);
            if (cur.endDate() == null) {
                throw new IllegalArgumentException("Only the most recent dosage period may be open");
            }
            if (cur.endDate().isAfter(next.startDate())) {
                throw new IllegalArgumentException("Dosage periods must not overlap");
            }
        }
    }
}
