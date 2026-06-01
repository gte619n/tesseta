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
    // Backend-optional fields kept nullable so a sparse phase can't fail the
    // deep-goal Moshi parse. The UI reads dates through parseDate()/
    // formatDateRange(), both String?-safe.
    val description: String? = null,
    val orderIndex: Int,
    val status: PhaseStatus,
    val targetStartDate: String? = null,
    val targetEndDate: String? = null,
    val completedAt: String? = null,
    val stepOrder: List<String> = emptyList(),
    val steps: List<Step> = emptyList(),
)

/** Shallow goal — the list/card shape (no phases). */
data class Goal(
    val goalId: String,
    val title: String,
    // Optional on the backend (CreateGoalRequest doesn't require it) and the
    // PATCH response can echo it back null — keep nullable so a single sparse
    // goal doesn't fail the whole `List<Goal>` Moshi parse and blank the list.
    val description: String? = null,
    val domain: GoalDomain,
    val status: GoalStatus,
    val startDate: String? = null,
    val targetDate: String? = null,
    // Server timestamps: the PATCH path returns `updatedAt` null (it's filled
    // by persistence on save, not echoed), and a just-created goal may read
    // back before its serverTimestamp() sentinel resolves. Nullable so Moshi
    // tolerates it; the UI never displays these.
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val completedAt: String? = null,
    val phaseOrder: List<String> = emptyList(),
    val source: GoalSource,
)

/** Deep goal — the roadmap shape (phases + steps). Mirrors GoalDeepResponse. */
data class GoalDeep(
    val goalId: String,
    val title: String,
    val description: String? = null,
    val domain: GoalDomain,
    val status: GoalStatus,
    val startDate: String? = null,
    val targetDate: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
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
