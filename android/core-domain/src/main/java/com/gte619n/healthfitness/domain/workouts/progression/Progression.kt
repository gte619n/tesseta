package com.gte619n.healthfitness.domain.workouts.progression

/**
 * The backend's weekly analysis for one movement pattern (e.g. PUSH_HORIZONTAL):
 * the volume trend, a fatigue read, and whether it proposes moving the working-set
 * target up/down (or deloading) for the coming week. Read-only.
 */
data class PatternReview(
    /** Movement-pattern enum name, e.g. "PUSH_HORIZONTAL". */
    val pattern: String,
    /** "RISING" | "FLAT" | "FALLING" | "UNKNOWN". */
    val trend: String,
    val weeklySlopePct: Double,
    val fatigueIndex: Double,
    val currentTarget: Int,
    val proposedTarget: Int,
    val deload: Boolean,
    val reasoning: String,
)

/**
 * The active training block's parameters: the mode driving progression, the
 * per-pattern rep ranges + weekly-set ceilings, and the RIR caps by exercise
 * class. [manualOverride] is true once the user pins the mode by hand.
 */
data class BlockParameters(
    /** "GAINING" | "RECOMP" | "MAINTENANCE" | "RECOVERY". */
    val mode: String,
    val expectedDriftPerDay: Double,
    /** "ADD_LOAD" | "HOLD_LOAD_AT_LOWER_RIR". */
    val successCriterion: String,
    val manualOverride: Boolean,
    /** Pattern enum name → (min, max) reps. */
    val repRanges: Map<String, Pair<Int, Int>>,
    /** "COMPOUND" | "ISOLATION" → RIR cap. */
    val rirCaps: Map<String, Double>,
    /** Pattern enum name → weekly working-set ceiling. */
    val weeklyCeiling: Map<String, Int>,
)
