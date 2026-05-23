package com.gte619n.healthfitness.core.medication;

import java.time.LocalDate;
import java.util.List;

/**
 * 30-day adherence summary for sparkline display.
 */
public record AdherenceSummary(
    List<DayAdherence> last30Days,
    double percentage               // 0.0 to 100.0
) {
    public record DayAdherence(
        LocalDate date,
        boolean taken
    ) {}
}
