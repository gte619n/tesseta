package com.gte619n.healthfitness.core.medication;

/**
 * Reason for discontinuing a medication.
 */
public enum DiscontinueReason {
    COMPLETED,      // Finished course (antibiotics, etc.)
    SIDE_EFFECTS,   // Adverse reactions
    SWITCHED,       // Changed to different medication
    COST,           // Too expensive
    OTHER           // Other reason (see notes)
}
