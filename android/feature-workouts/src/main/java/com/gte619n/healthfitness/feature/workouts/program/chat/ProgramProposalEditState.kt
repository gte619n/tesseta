package com.gte619n.healthfitness.feature.workouts.program.chat

import androidx.compose.runtime.mutableStateOf
import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram

/**
 * Mutable, Compose-observable editor for the workout-program proposal card
 * (IMPL-AND-18; mirrors feature-goals' ProposalEditState). The proposal is a
 * deep program tree; the user edits the program title/description/startDate and
 * the concrete prescribed weights, while the rest of the tree (phases → days →
 * blocks → other prescription fields, nutrition guidance) round-trips unchanged.
 *
 * Built from the immutable streamed [WorkoutProgram]; converted back to a
 * [WorkoutProgram] for commit so the IMPL-18 additive fields (targetWeightLbs /
 * loadBasis, per-phase + program nutritionGuidance) are preserved.
 */
class PrescriptionEdit(
    private val source: Prescription,
) {
    /** Editable concrete load (raw text; parsed on commit). Empty → null (RPE fallback). */
    val targetWeightLbs = mutableStateOf(source.targetWeightLbs?.let { trimNumber(it) } ?: "")

    /** The history basis ("why"), shown on tap (R6). Read-only. */
    val loadBasis: String? = source.loadBasis

    /** The exercise display name (embedded summary), falling back to the id. */
    fun exerciseName(): String = source.exercise?.name ?: source.exerciseId

    /** The source prescription, for read-only summary formatting (reps/intensity/rest). */
    fun prescription(): Prescription = source

    /** Hint text for the weight field: the RPE/%1RM fallback when no concrete load. */
    fun weightPlaceholder(): String = when (source.intensity?.kind?.name) {
        "RPE" -> source.intensity?.value?.let { "RPE ${trimNumber(it)}" } ?: "RPE-based"
        "PERCENT_1RM" -> source.intensity?.value?.let { "${trimNumber(it)}% 1RM" } ?: "%1RM-based"
        else -> "RPE-based"
    }

    fun toPrescription(): Prescription =
        source.copy(targetWeightLbs = targetWeightLbs.value.trim().toDoubleOrNull())
}

class BlockEdit(private val source: Block) {
    val prescriptions: List<PrescriptionEdit> = source.prescriptions.map { PrescriptionEdit(it) }

    fun toBlock(): Block = source.copy(prescriptions = prescriptions.map { it.toPrescription() })
}

class DayEdit(private val source: WorkoutDay) {
    val blocks: List<BlockEdit> = source.blocks.map { BlockEdit(it) }

    fun dayLabelOrNull(): String? = source.label.takeIf { it.isNotBlank() }
        ?: source.dayOfWeek.name

    fun toDay(): WorkoutDay = source.copy(blocks = blocks.map { it.toBlock() })
}

class PhaseEdit(private val source: ProgramPhase) {
    val days: List<DayEdit> = source.days.map { DayEdit(it) }

    fun titleOrNull(): String? = source.title.takeIf { it.isNotBlank() }

    fun nutritionGuidanceOrNull() = source.nutritionGuidance?.takeUnless { it.isEmpty }

    fun toPhase(): ProgramPhase = source.copy(days = days.map { it.toDay() })
}

class ProgramProposalEdit(private val source: WorkoutProgram) {
    val title = mutableStateOf(source.title)
    val description = mutableStateOf(source.description.orEmpty())
    val startDate = mutableStateOf(source.startDate?.toString())
    val phases: List<PhaseEdit> = source.phases.map { PhaseEdit(it) }

    /** Convenience for the card: the program-level nutrition fallback (display-only). */
    val nutritionGuidance = source.nutritionGuidance?.takeUnless { it.isEmpty }

    fun toProgram(): WorkoutProgram = source.copy(
        title = title.value,
        description = description.value.ifBlank { null },
        startDate = startDate.value?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
        phases = phases.map { it.toPhase() },
    )

    companion object {
        fun from(program: WorkoutProgram) = ProgramProposalEdit(program)
    }
}

/** "150.0" -> "150", "99.5" -> "99.5". */
private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
