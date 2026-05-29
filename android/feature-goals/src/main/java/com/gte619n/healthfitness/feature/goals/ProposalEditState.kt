package com.gte619n.healthfitness.feature.goals

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.gte619n.healthfitness.domain.goals.Comparator
import com.gte619n.healthfitness.domain.goals.GoalDomain
import com.gte619n.healthfitness.domain.goals.GoalProposal
import com.gte619n.healthfitness.domain.goals.ProposalMetric
import com.gte619n.healthfitness.domain.goals.ProposalPhase
import com.gte619n.healthfitness.domain.goals.ProposalStep
import com.gte619n.healthfitness.domain.goals.StepKind

// Mutable, Compose-observable editor state for GoalProposalCard. Built from the
// immutable GoalProposal that the backend streamed (and re-built when the
// commit endpoint returns a re-flagged proposal), and converted back to a
// GoalProposal for the commit POST. validationError strings are read-only — the
// editor surfaces them inline; the backend recomputes them on commit.

class StepEdit(
    title: String,
    kind: StepKind,
    metricKey: String?,
    comparator: Comparator?,
    targetValue: String,
    windowDays: String,
    val validationError: String? = null,
    val metricValidationError: String? = null,
) {
    var title = mutableStateOf(title)
    var kind = mutableStateOf(kind)
    var metricKey = mutableStateOf(metricKey)
    var comparator = mutableStateOf(comparator)
    var targetValue = mutableStateOf(targetValue) // raw text; parsed on commit
    var windowDays = mutableStateOf(windowDays)

    fun toProposal(): ProposalStep {
        val k = kind.value
        val metric = if (k != StepKind.MANUAL) {
            ProposalMetric(
                metricKey = metricKey.value,
                comparator = comparator.value,
                targetValue = targetValue.value.toDoubleOrNull(),
                windowDays = if (k == StepKind.SUSTAINED) windowDays.value.toIntOrNull() else null,
                countFrom = null, // backend defaults countFrom to now for COUNT
            )
        } else {
            null
        }
        return ProposalStep(title = title.value, kind = k, metric = metric)
    }

    companion object {
        fun from(s: ProposalStep) = StepEdit(
            title = s.title.orEmpty(),
            kind = s.kind,
            metricKey = s.metric?.metricKey,
            comparator = s.metric?.comparator,
            targetValue = s.metric?.targetValue?.let { trimNumber(it) } ?: "",
            windowDays = s.metric?.windowDays?.toString() ?: "",
            validationError = s.validationError,
            metricValidationError = s.metric?.validationError,
        )

        fun blank() = StepEdit(
            title = "",
            kind = StepKind.MANUAL,
            metricKey = null,
            comparator = null,
            targetValue = "",
            windowDays = "",
        )
    }
}

class PhaseEdit(
    title: String,
    description: String,
    targetStartDate: String?,
    targetEndDate: String?,
    val steps: SnapshotStateList<StepEdit>,
    val validationError: String? = null,
) {
    var title = mutableStateOf(title)
    var description = mutableStateOf(description)
    var targetStartDate = mutableStateOf(targetStartDate) // ISO "yyyy-MM-dd" or null
    var targetEndDate = mutableStateOf(targetEndDate)

    fun toProposal(): ProposalPhase = ProposalPhase(
        title = title.value,
        description = description.value,
        targetStartDate = targetStartDate.value,
        targetEndDate = targetEndDate.value,
        steps = steps.map { it.toProposal() },
    )

    companion object {
        fun from(p: ProposalPhase) = PhaseEdit(
            title = p.title.orEmpty(),
            description = p.description.orEmpty(),
            targetStartDate = p.targetStartDate,
            targetEndDate = p.targetEndDate,
            steps = mutableStateListOf<StepEdit>().apply {
                p.steps.forEach { add(StepEdit.from(it)) }
            },
            validationError = p.validationError,
        )

        fun blank() = PhaseEdit(
            title = "",
            description = "",
            targetStartDate = null,
            targetEndDate = null,
            steps = mutableStateListOf(StepEdit.blank()),
        )
    }
}

class ProposalEdit(
    title: String,
    description: String,
    domain: GoalDomain,
    targetDate: String?,
    val phases: SnapshotStateList<PhaseEdit>,
    val validationError: String? = null,
) {
    var title = mutableStateOf(title)
    var description = mutableStateOf(description)
    var domain = mutableStateOf(domain)
    var targetDate = mutableStateOf(targetDate)

    fun toProposal(): GoalProposal = GoalProposal(
        title = title.value,
        description = description.value,
        domain = domain.value,
        targetDate = targetDate.value,
        phases = phases.map { it.toProposal() },
    )

    companion object {
        fun from(p: GoalProposal) = ProposalEdit(
            title = p.title.orEmpty(),
            description = p.description.orEmpty(),
            domain = p.domain ?: GoalDomain.OTHER,
            targetDate = p.targetDate,
            phases = mutableStateListOf<PhaseEdit>().apply {
                p.phases.forEach { add(PhaseEdit.from(it)) }
            },
            validationError = p.validationError,
        )

        fun blank() = ProposalEdit(
            title = "",
            description = "",
            domain = GoalDomain.OTHER,
            targetDate = null,
            phases = mutableStateListOf(PhaseEdit.blank()),
        )
    }
}

/** "150.0" -> "150", "99.5" -> "99.5". Keeps the editor field tidy. */
private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
