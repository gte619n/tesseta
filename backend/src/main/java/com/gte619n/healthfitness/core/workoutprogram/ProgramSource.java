package com.gte619n.healthfitness.core.workoutprogram;

public enum ProgramSource {
    MANUAL, AI_GENERATED, AI_ASSISTED,
    /**
     * Bulk-imported external history (WorkoutHistoryImporter). Its logged weights
     * follow the SOURCE app's convention, which for dumbbell/dual-cable lifts is
     * the TOTAL (both-hands) load — NOT the per-hand number the in-app logger
     * records. Reporting must therefore skip the per-hand→total ×2 for these
     * programs (IMPL-PROG-LOAD-01 follow-up) so imported dumbbell history doesn't
     * read double.
     */
    IMPORTED
}
