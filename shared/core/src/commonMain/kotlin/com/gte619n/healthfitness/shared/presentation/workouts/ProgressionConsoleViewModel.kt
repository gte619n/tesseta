package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.ProgressionRepository
import com.gte619n.healthfitness.shared.data.WorkoutGoalsRepository
import com.gte619n.healthfitness.shared.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.shared.domain.workouts.progression.EnergyBalance
import com.gte619n.healthfitness.shared.domain.workouts.progression.ExerciseStrength
import com.gte619n.healthfitness.shared.domain.workouts.progression.PatternReview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * IMPL-IOS-01 Phase 3 Wave D(iii) — shared port of the Android
 * `feature-workouts/.../progression/ProgressionConsoleViewModel`.
 *
 * Drives the progression console: this week's per-pattern review, per-lift
 * estimated strength (weights), the measured energy balance, the active goal it
 * serves, and the training block's parameters. Week review + block are the
 * REQUIRED reads (a failure offers a retry); strength / energy / goal are
 * additive context that degrade quietly. Changing the mode PUTs the new
 * parameters and adopts the returned block.
 *
 * ANTI-DRIFT single-sourcing: the progression MATH (per-hand→total reporting,
 * realistic jumps, demonstrated-override) is the backend engine's, not the
 * client's — the client never re-derives a prescribed jump. What is
 * single-sourced HERE (so iOS + Android render one truth) is the *presentation*
 * derivation over the engine's outputs:
 *   - [formatLoadTrend]  — the block's expected e1RM drift → signed lb/week.
 *   - [PatternReviewRow] — the per-pattern current→proposed set target + tone.
 *   - [StrengthRow.perHandTotalLbs] / [displayLoad] — the per-hand→total (×2)
 *     reporting for dumbbell / dual-cable lifts (F: a per-hand estimate must be
 *     shown as the total lifted, not the per-hand number).
 *   - [pinnedDivergence] — pinned-vs-measured-mode advisory.
 * The SwiftUI + Compose views consume these prebaked strings/rows; neither
 * client re-implements the formatting.
 */
class ProgressionConsoleViewModel(
    private val repository: ProgressionRepository,
    private val goals: WorkoutGoalsRepository,
) : ViewModel() {

    /** The goal this progression is serving (for the mode↔goal linkage). */
    data class ActiveGoal(val title: String, val domain: String)

    /**
     * A prebaked per-pattern review row: the humanized pattern, the trend, the
     * "current → proposed" set target string, the deload flag, and the reasoning.
     */
    data class PatternReviewRow(
        val pattern: String,
        val patternLabel: String,
        val trend: String,
        val setTargetChange: String,
        val deload: Boolean,
        val reasoning: String,
    )

    /**
     * A prebaked strength row. [perHand] marks a dumbbell / dual-cable lift whose
     * engine e1RM is per-hand; [displayLoad] then reports the TOTAL lifted (×2)
     * with a "(2 × N/hand)" caption so a 45 lb dumbbell reads as "90 lb", not 45.
     */
    data class StrengthRow(
        val exerciseId: String,
        val name: String,
        val movementPattern: String?,
        val perHand: Boolean,
        val displayLoad: String,
        val perHandCaption: String?,
        val confidence: String,
    )

    data class State(
        val loading: Boolean = true,
        val weekReview: List<PatternReviewRow> = emptyList(),
        val block: BlockParameters? = null,
        val strength: List<StrengthRow> = emptyList(),
        val energy: EnergyBalance? = null,
        val goal: ActiveGoal? = null,
        val error: String? = null,
        /** A mode change is in flight (disables the toggle to avoid double taps). */
        val updatingMode: Boolean = false,
    ) {
        /** The measured mode, only meaningful once intake data has accrued. */
        val measuredMode: String?
            get() = energy?.takeIf { it.hasIntakeData }?.mode

        /** True when the pinned mode disagrees with the measured energy balance. */
        val pinnedDivergence: Boolean
            get() = block?.manualOverride == true && measuredMode != null && measuredMode != block.mode
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    /** (Re)load the console. Week review + block are required; the rest is additive. */
    fun load() {
        viewModelScope.launch {
            _state.update { State(loading = true) }
            val reviewResult = repository.weekReview()
            val blockResult = repository.blockParameters()

            val error = reviewResult.exceptionOrNull() ?: blockResult.exceptionOrNull()
            if (error != null) {
                _state.update {
                    State(loading = false, error = error.message ?: "Couldn't load progression")
                }
                return@launch
            }

            // Additive context — never fail the screen on these.
            val strength = repository.strength().getOrNull().orEmpty()
            val energy = repository.energyBalance().getOrNull()
            val goal = runCatching { goals.activeGoals() }.getOrNull()
                ?.firstOrNull()
                ?.let { ActiveGoal(it.title, it.domain) }

            _state.update {
                State(
                    loading = false,
                    weekReview = reviewResult.getOrNull().orEmpty().map(::toReviewRow),
                    block = blockResult.getOrNull(),
                    strength = strength.map(::toStrengthRow),
                    energy = energy,
                    goal = goal,
                )
            }
        }
    }

    /** Pin the training mode: PUT it, then adopt the refreshed block parameters. */
    fun updateMode(mode: String) {
        if (_state.value.updatingMode) return
        viewModelScope.launch {
            _state.update { it.copy(updatingMode = true) }
            repository.updateBlockParameters(mode)
                .onSuccess { block -> _state.update { it.copy(updatingMode = false, block = block) } }
                .onFailure { e ->
                    _state.update { it.copy(updatingMode = false, error = e.message ?: "Couldn't update mode") }
                }
        }
    }

    // --- Prebaked-row derivation (single-sourced formatting) -----------------

    private fun toReviewRow(r: PatternReview): PatternReviewRow = PatternReviewRow(
        pattern = r.pattern,
        patternLabel = humanize(r.pattern),
        trend = r.trend,
        setTargetChange = "${r.currentTarget} → ${r.proposedTarget}",
        deload = r.deload,
        reasoning = r.reasoning,
    )

    private fun toStrengthRow(s: ExerciseStrength): StrengthRow {
        val perHand = isPerHand(s.movementPattern, s.name)
        val perHandLbs = s.e1rmLbs.roundToInt()
        val displayLbs = if (perHand) totalFromPerHand(s.e1rmLbs).roundToInt() else perHandLbs
        return StrengthRow(
            exerciseId = s.exerciseId,
            name = s.name,
            movementPattern = s.movementPattern?.let(::humanize),
            perHand = perHand,
            displayLoad = "$displayLbs lb",
            perHandCaption = if (perHand) "2 × $perHandLbs lb / hand" else null,
            confidence = s.confidence,
        )
    }

    companion object {
        val MODES: List<String> = listOf("GAINING", "RECOMP", "MAINTENANCE", "RECOVERY")

        /**
         * Per-hand→total: an implement held one-per-hand (dumbbell / dual cable)
         * has a per-hand e1RM belief; the total load moved is ×2. Detected by the
         * exercise name since the engine reports per-implement.
         */
        internal fun isPerHand(movementPattern: String?, name: String): Boolean {
            val n = name.lowercase()
            return "dumbbell" in n || "db " in n || n.startsWith("db") ||
                "dual cable" in n || "dual-cable" in n
        }

        internal fun totalFromPerHand(perHandLbs: Double): Double = perHandLbs * 2

        /** e1RM drift the block expects, shown as a signed per-week load trend. */
        fun formatLoadTrend(driftPerDay: Double): String {
            val perWeek = driftPerDay * 7
            if (abs(perWeek) < 0.05) return "Holding — no planned change"
            val sign = if (perWeek > 0) "+" else "−"
            return "$sign${formatOneDecimal(abs(perWeek))} lb / week"
        }

        fun formatRir(cap: Double): String = trimTrailingZeros(cap)

        /** "PUSH_HORIZONTAL" → "Push Horizontal"; keeps acronyms upper-cased. */
        fun humanize(enumName: String): String {
            val acronyms = setOf("RIR", "RPE", "1RM")
            return enumName.split('_')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    if (word.uppercase() in acronyms) word.uppercase()
                    else word.lowercase().replaceFirstChar { it.uppercase() }
                }
        }

        private fun trimTrailingZeros(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        // kotlin.math has no String.format in commonMain; hand-round to 1 dp.
        private fun formatOneDecimal(v: Double): String {
            val scaled = (v * 10).roundToLong()
            val whole = scaled / 10
            val frac = (abs(scaled) % 10)
            return "$whole.$frac"
        }
    }
}
