package com.gte619n.healthfitness.domain.goals

// Kotlin twin of the backend GoalProposalDto (see backend
// api/goals/dto/GoalProposalDto.java). Streamed as the SSE `proposal` event's
// JSON and POSTed back (user-edited) to the commit endpoint. Each level carries
// an optional `validationError` so the editable card can flag offending fields
// inline rather than dropping them (IMPL-12 acceptance criteria).
//
// Dates are LocalDate-style ISO strings ("2026-09-30"); countFrom is an Instant
// ISO string. Kept as strings here for the same reason as Goals.kt — parsed in
// the UI layer (assumption 18).

data class GoalProposal(
    val title: String? = null,
    val description: String? = null,
    val domain: GoalDomain? = null,
    val targetDate: String? = null,
    val phases: List<ProposalPhase> = emptyList(),
    val validationError: String? = null,
)

data class ProposalPhase(
    val title: String? = null,
    val description: String? = null,
    val targetStartDate: String? = null,
    val targetEndDate: String? = null,
    val steps: List<ProposalStep> = emptyList(),
    val validationError: String? = null,
)

data class ProposalStep(
    val title: String? = null,
    val kind: StepKind = StepKind.MANUAL,
    val metric: ProposalMetric? = null,
    val validationError: String? = null,
)

data class ProposalMetric(
    val metricKey: String? = null,
    val comparator: Comparator? = null,
    val targetValue: Double? = null,
    val windowDays: Int? = null,
    val countFrom: String? = null,
    val validationError: String? = null,
)

/** Response body of POST .../chat/{threadId}/commit on success. */
data class CommitResponse(val goalId: String)

/**
 * The fixed metric-key registry the backend resolver understands (IMPL-12).
 * Used by the proposal-card metric editor's dropdown so the user can only bind
 * Steps to real metrics. Mirrors the spec's registry verbatim.
 */
object MetricRegistry {
    val keys: List<String> = listOf(
        "body.weight",
        "body.bodyFatPct",
        "body.leanMass",
        "blood.ldl",
        "blood.apoB",
        "blood.hba1c",
        "blood.hsCRP",
        "vitals.restingHr",
        "vitals.hrv",
        "vitals.sleepScore",
        "workouts.count",
        "workouts.weeklyVolume",
        "nutrition.proteinAvg7d",
        "nutrition.carbsAvg7d",
        "nutrition.fatAvg7d",
        "nutrition.caloriesAvg7d",
        "meds.adherence30d",
    )
}
