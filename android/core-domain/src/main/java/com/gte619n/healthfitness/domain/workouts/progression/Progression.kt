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

/**
 * Per-exercise estimated 1RM — the engine's authoritative "weight" belief for a
 * lift, named and confidence-graded. Sorted heaviest-first by the backend.
 */
data class ExerciseStrength(
    val exerciseId: String,
    val name: String,
    /** Movement-pattern enum name, e.g. "SQUAT"; null if the exercise is gone. */
    val movementPattern: String?,
    val e1rmLbs: Double,
    /** "LOW" | "MEDIUM" | "HIGH" — from the belief's relative uncertainty. */
    val confidence: String,
    val observationCount: Int,
)

/**
 * The engine's measured energy state. [mode] is what the measured balance
 * implies; the pinned/effective block mode may differ. [hasIntakeData] is false
 * until enough intake accrues — the balance is not meaningful yet.
 */
data class EnergyBalance(
    val maintenanceKcal: Double,
    val meanIntakeKcal: Double,
    val balanceKcal: Double,
    /** "GAINING" | "RECOMP" | "MAINTENANCE" | "RECOVERY". */
    val mode: String,
    val hasIntakeData: Boolean,
)
