package com.gte619n.healthfitness.core.medication;

import java.util.List;

/**
 * Full frequency configuration for a medication.
 */
public record FrequencyConfig(
    FrequencyType type,
    Integer timesPerPeriod,         // e.g., 2 for "2x daily"
    List<DayOfWeek> specificDays,   // e.g., [MON, WED, FRI]
    CycleConfig cycle               // for CYCLE type only
) {
    /**
     * Create a simple daily frequency.
     */
    public static FrequencyConfig daily(int times) {
        return new FrequencyConfig(FrequencyType.DAILY, times, null, null);
    }

    /**
     * Create a weekly frequency with specific days.
     */
    public static FrequencyConfig weekly(List<DayOfWeek> days) {
        return new FrequencyConfig(FrequencyType.WEEKLY, days.size(), days, null);
    }

    /**
     * Create a PRN (as needed) frequency.
     */
    public static FrequencyConfig prn() {
        return new FrequencyConfig(FrequencyType.PRN, null, null, null);
    }
}
