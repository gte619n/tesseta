package com.gte619n.healthfitness.domain.goals

import com.squareup.moshi.Json

// Goals domain models (IMPL-12). These mirror the backend JSON contract
// exactly — see web/lib/types/goals.ts (GoalResponse, GoalDeepResponse,
// PhaseResponse, StepResponse, StepMetricBinding) — and double as the Moshi
// wire types. Dates/timestamps are kept as ISO-8601 strings and parsed in the
// UI layer, per IMPL-12 remaining-assumptions item 18.

enum class GoalDomain {
    CARDIOVASCULAR,
    BODY_COMPOSITION,
    STRENGTH,
    METABOLIC,
    SLEEP,
    LONGEVITY,
    OTHER,
    ;

    val label: String
        get() = when (this) {
            CARDIOVASCULAR -> "Cardiovascular"
            BODY_COMPOSITION -> "Body composition"
            STRENGTH -> "Strength"
            METABOLIC -> "Metabolic"
            SLEEP -> "Sleep"
            LONGEVITY -> "Longevity"
            OTHER -> "Other"
        }
}

enum class GoalStatus { ACTIVE, COMPLETED, ARCHIVED }

enum class GoalSource { MANUAL, AI_GENERATED, AI_ASSISTED }

enum class PhaseStatus { LOCKED, ACTIVE, COMPLETED }

enum class StepKind { MANUAL, THRESHOLD, SUSTAINED, COUNT }

enum class Comparator {
    LT, LTE, GT, GTE, EQ;

    val symbol: String
        get() = when (this) {
            LT -> "<"
            LTE -> "≤"
            GT -> ">"
            GTE -> "≥"
            EQ -> "="
        }
}

data class StepMetricBinding(
    val metricKey: String,
    val comparator: Comparator,
    val targetValue: Double,
    val windowDays: Int? = null,
    val countFrom: String? = null,
)

data class Step(
    val stepId: String,
    val phaseId: String,
    val goalId: String,
    val title: String,
    val orderIndex: Int,
    val kind: StepKind,
    val done: Boolean,
    val doneAt: String? = null,
    val manualOverride: Boolean = false,
    val metric: StepMetricBinding? = null,
    val metricRegressed: Boolean? = null,
)

data class Phase(
    val phaseId: String,
    val goalId: String,
    val title: String,
    val description: String,
    val orderIndex: Int,
    val status: PhaseStatus,
    val targetStartDate: String,
    val targetEndDate: String,
    val completedAt: String? = null,
    val stepOrder: List<String> = emptyList(),
    val steps: List<Step> = emptyList(),
)

/** Shallow goal — the list/card shape (no phases). */
data class Goal(
    val goalId: String,
    val title: String,
    val description: String,
    val domain: GoalDomain,
    val status: GoalStatus,
    val startDate: String,
    val targetDate: String,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String? = null,
    val phaseOrder: List<String> = emptyList(),
    val source: GoalSource,
)

/** Deep goal — the roadmap shape (phases + steps). Mirrors GoalDeepResponse. */
data class GoalDeep(
    val goalId: String,
    val title: String,
    val description: String,
    val domain: GoalDomain,
    val status: GoalStatus,
    val startDate: String,
    val targetDate: String,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String? = null,
    val phaseOrder: List<String> = emptyList(),
    val source: GoalSource,
    val phases: List<Phase> = emptyList(),
)

/** Body for PATCH .../steps/{sid}. Either toggle done, or reset to auto. */
data class StepPatchRequest(
    @Json(name = "done") val done: Boolean? = null,
    @Json(name = "resetToAuto") val resetToAuto: Boolean? = null,
)
