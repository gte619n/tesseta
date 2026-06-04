package com.gte619n.healthfitness.core.medication;

/**
 * How often a medication is taken.
 */
public enum FrequencyType {
    DAILY,      // X times per day
    WEEKLY,     // X times per week
    MONTHLY,    // X times per month
    PRN,        // As needed (pro re nata) - not scheduled
    CYCLE       // Cycling protocol (e.g., 4 weeks on, 2 weeks off)
}
