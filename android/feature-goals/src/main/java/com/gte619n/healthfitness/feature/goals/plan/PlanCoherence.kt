package com.gte619n.healthfitness.feature.goals.plan

import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.workouts.progression.EnergyBalance
import kotlin.math.abs
import kotlin.math.roundToInt

// Pure model + divergence logic for the plan-coherence view (goal-scoped),
// ported from the web lib/plan-coherence.ts. No Android/Compose deps here so it
// stays unit-testable: given the assembled chain, produce the ordered flags
// (actionable first, then warnings, then informational).

enum class PlanSeverity { ACTION, WARN, INFO }

enum class PlanActionKind { APPLY_PROGRAM_NUTRITION, SET_TARGET_FROM_MAINTENANCE }

/** One program phase's nutrition guidance, flattened for the view. */
data class PhaseGuidance(
    val kcal: Int?,
    val proteinG: Int?,
    val carbsG: Int?,
    val fatG: Int?,
)

/** The assembled chain the coherence UI renders (all pieces optional). */
data class PlanChain(
    val goalTitle: String?,
    val goalDomain: String?,
    val programTitle: String?,
    val phaseTitle: String?,
    val guidance: PhaseGuidance?,
    val target: Macros?,
    val todayTotals: Macros?,
    val energy: EnergyBalance?,
    val blockMode: String?,
    val blockManualOverride: Boolean,
)

data class PlanFlag(
    val id: String,
    val severity: PlanSeverity,
    /** Semantic key mapped to a Material icon at render time. */
    val icon: String,
    val title: String,
    val detail: String,
    val hint: String? = null,
    val actionLabel: String? = null,
    val action: PlanActionKind? = null,
)

// kcal a macro bundle implies, using stored calories or the Atwater 4/4/9
// fallback. Null when there's nothing to compute from.
fun kcalOf(m: Macros?): Double? {
    if (m == null) return null
    m.caloriesKcal?.let { return it }
    if (m.proteinGrams == null && m.carbsGrams == null && m.fatGrams == null) return null
    return (m.proteinGrams ?: 0.0) * 4 + (m.carbsGrams ?: 0.0) * 4 + (m.fatGrams ?: 0.0) * 9
}

private fun guidanceKcal(g: PhaseGuidance?): Double? {
    if (g == null) return null
    g.kcal?.let { return it.toDouble() }
    if (g.proteinG == null && g.carbsG == null && g.fatG == null) return null
    return (g.proteinG ?: 0) * 4.0 + (g.carbsG ?: 0) * 4.0 + (g.fatG ?: 0) * 9.0
}

private const val TARGET_DRIFT_KCAL = 60.0 // program vs. current target
private const val DEFICIT_EPSILON_KCAL = 75.0 // "is the target actually below maintenance?"

private val SEVERITY_ORDER = mapOf(
    PlanSeverity.ACTION to 0,
    PlanSeverity.WARN to 1,
    PlanSeverity.INFO to 2,
)

fun computeFlags(chain: PlanChain): List<PlanFlag> {
    val flags = mutableListOf<PlanFlag>()

    val targetKcal = kcalOf(chain.target)
    val progKcal = guidanceKcal(chain.guidance)
    val maint = chain.energy?.takeIf { it.hasIntakeData }?.maintenanceKcal?.roundToInt()

    // No program linked to this goal.
    if (chain.programTitle == null) {
        flags.add(
            PlanFlag(
                id = "no-program",
                severity = PlanSeverity.INFO,
                icon = "barbell",
                title = "No active program",
                detail = "There's no training or per-phase nutrition guidance for this goal yet.",
                hint = "Design a program and attach it to this goal.",
            ),
        )
    }

    // Calorie target vs. program guidance.
    if (chain.programTitle != null && progKcal != null && targetKcal == null) {
        flags.add(
            PlanFlag(
                id = "no-target-has-guidance",
                severity = PlanSeverity.ACTION,
                icon = "target",
                title = "No calorie target set",
                detail = "Your program's current phase suggests ${fmt(progKcal)} kcal/day.",
                hint = "Apply it so your daily logging has something to measure against.",
                actionLabel = "Apply",
                action = PlanActionKind.APPLY_PROGRAM_NUTRITION,
            ),
        )
    } else if (
        chain.programTitle != null && progKcal != null && targetKcal != null &&
        abs(progKcal - targetKcal) > TARGET_DRIFT_KCAL
    ) {
        flags.add(
            PlanFlag(
                id = "target-drift",
                severity = PlanSeverity.ACTION,
                icon = "arrows-diff",
                title = "Target doesn't match your program",
                detail = "Phase suggests ${fmt(progKcal)} kcal, but your target is ${fmt(targetKcal)}.",
                hint = "Usually means the phase changed — re-apply to sync them.",
                actionLabel = "Apply",
                action = PlanActionKind.APPLY_PROGRAM_NUTRITION,
            ),
        )
    }

    // No target at all, but a measured maintenance to seed from.
    if (targetKcal == null && progKcal == null && maint != null) {
        flags.add(
            PlanFlag(
                id = "no-target-has-maintenance",
                severity = PlanSeverity.ACTION,
                icon = "flame",
                title = "No calorie target set",
                detail = "The engine measures your maintenance at about ${fmt(maint.toDouble())} kcal/day.",
                hint = "Seed a target from it, then adjust for your goal.",
                actionLabel = "Set target",
                action = PlanActionKind.SET_TARGET_FROM_MAINTENANCE,
            ),
        )
    }

    // Target vs. measured maintenance (+ goal intent).
    if (targetKcal != null && maint != null) {
        val delta = targetKcal - maint
        val cutLeaning = chain.goalDomain == "BODY_COMPOSITION"
        if (cutLeaning && delta > -DEFICIT_EPSILON_KCAL) {
            flags.add(
                PlanFlag(
                    id = "goal-wants-deficit",
                    severity = PlanSeverity.WARN,
                    icon = "trending-down",
                    title = "Target isn't in a deficit",
                    detail = "Your target (${fmt(targetKcal)}) sits at or above your measured maintenance (~${fmt(maint.toDouble())}).",
                    hint = "A body-composition goal needs a deficit to lose fat.",
                ),
            )
        } else {
            flags.add(
                PlanFlag(
                    id = "target-vs-maintenance",
                    severity = PlanSeverity.INFO,
                    icon = "scale",
                    title = "Target vs. measured maintenance",
                    detail = "Target ${fmt(targetKcal)} kcal — ${describeDelta(delta.toDouble())} your measured maintenance of ~${fmt(maint.toDouble())}.",
                    hint = "Measured from your logged intake and bodyweight trend.",
                ),
            )
        }
    }

    // Engine mode: pinned vs. measured.
    val energy = chain.energy
    if (chain.blockManualOverride && energy != null && energy.hasIntakeData &&
        chain.blockMode != null && chain.blockMode != energy.mode
    ) {
        flags.add(
            PlanFlag(
                id = "mode-pinned-diverges",
                severity = PlanSeverity.WARN,
                icon = "pin",
                title = "Engine mode is pinned",
                detail = "Pinned to ${titleCase(chain.blockMode)}, but your measured balance suggests ${titleCase(energy.mode)}.",
                hint = "Unpin it on the Progression Engine to let it auto-adjust.",
            ),
        )
    }

    return flags.sortedBy { SEVERITY_ORDER[it.severity] ?: 9 }
}

private fun describeDelta(delta: Double): String {
    val rounded = delta.roundToInt()
    if (abs(rounded) <= DEFICIT_EPSILON_KCAL) return "roughly at"
    return if (rounded > 0) "a ${fmt(rounded.toDouble())} kcal surplus over"
    else "a ${fmt(abs(rounded).toDouble())} kcal deficit below"
}

internal fun fmt(n: Double): String = "%,d".format(n.roundToInt())

internal fun titleCase(s: String): String =
    s.split("_").filter { it.isNotBlank() }.joinToString(" ") { w ->
        w.lowercase().replaceFirstChar { it.uppercase() }
    }
