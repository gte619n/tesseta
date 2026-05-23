package com.gte619n.healthfitness.core.medication;

/**
 * Time windows for dose scheduling.
 * Fixed ranges: morning=6-10am, afternoon=12-3pm, evening=5-8pm, bedtime=9-11pm
 */
public enum TimeWindow {
    MORNING,    // 6:00 AM - 10:00 AM
    AFTERNOON,  // 12:00 PM - 3:00 PM
    EVENING,    // 5:00 PM - 8:00 PM
    BEDTIME     // 9:00 PM - 11:00 PM
}
