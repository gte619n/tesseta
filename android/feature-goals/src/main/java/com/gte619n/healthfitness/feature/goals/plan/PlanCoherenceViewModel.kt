package com.gte619n.healthfitness.feature.goals.plan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.goals.GoalsRepository
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.data.workouts.progression.ProgressionRepository
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.feature.goals.GOAL_ID_ARG
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Assembles the goal-scoped plan-coherence chain — the program linked to this
 * goal (+ its current phase's nutrition guidance) together with the calorie
 * target, today's intake, and the engine's measured energy state — and reconciles
 * the divergences. Cross-feature: reads goals, workout-program, nutrition, and
 * progression repositories (all in core-data). Every piece is best-effort.
 */
@HiltViewModel
class PlanCoherenceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val goals: GoalsRepository,
    private val programs: WorkoutProgramRepository,
    private val nutrition: NutritionRepository,
    private val progression: ProgressionRepository,
) : ViewModel() {

    private val goalId: String = checkNotNull(savedStateHandle[GOAL_ID_ARG]) {
        "PlanCoherenceViewModel requires a '$GOAL_ID_ARG' nav argument"
    }

    data class State(
        val loading: Boolean = true,
        val chain: PlanChain? = null,
        val flags: List<PlanFlag> = emptyList(),
        val pendingAction: PlanActionKind? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val goal = runCatching { goals.goalDeep(goalId) }.getOrNull()

            // The program linked to this goal — prefer an ACTIVE one.
            val linked = programs.list().getOrDefault(emptyList())
                .filter { it.goalId == goalId }
            val chosen = linked.firstOrNull { it.status == ProgramStatus.ACTIVE }
                ?: linked.firstOrNull()

            var programTitle: String? = null
            var phaseTitle: String? = null
            var guidance: PhaseGuidance? = null
            if (chosen != null) {
                programTitle = chosen.title
                val deep = programs.get(chosen.programId).getOrNull()
                val activePhase = deep?.phases?.firstOrNull { it.status == ProgramPhaseStatus.ACTIVE }
                phaseTitle = activePhase?.title
                val g = activePhase?.nutritionGuidance
                    ?: deep?.nutritionGuidance
                    ?: chosen.nutritionGuidance
                guidance = g?.let { PhaseGuidance(it.kcal, it.proteinG, it.carbsG, it.fatG) }
            }

            val target = runCatching { nutrition.target() }.getOrNull()
            val today = runCatching { nutrition.day(LocalDate.now().toString()) }.getOrNull()
            val energy = progression.energyBalance().getOrNull()
            val block = progression.blockParameters().getOrNull()

            val chain = PlanChain(
                goalTitle = goal?.title,
                goalDomain = goal?.domain?.name,
                programTitle = programTitle,
                phaseTitle = phaseTitle,
                guidance = guidance,
                target = target,
                todayTotals = today?.totals,
                energy = energy,
                blockMode = block?.mode,
                blockManualOverride = block?.manualOverride ?: false,
            )
            _state.update {
                it.copy(loading = false, chain = chain, flags = computeFlags(chain), pendingAction = null)
            }
        }
    }

    fun reconcile(kind: PlanActionKind) {
        if (_state.value.pendingAction != null) return
        viewModelScope.launch {
            _state.update { it.copy(pendingAction = kind) }
            runCatching {
                when (kind) {
                    PlanActionKind.APPLY_PROGRAM_NUTRITION -> goals.applyNutrition(goalId)
                    PlanActionKind.SET_TARGET_FROM_MAINTENANCE -> {
                        val e = _state.value.chain?.energy
                        if (e != null && e.hasIntakeData) {
                            val kcal = e.maintenanceKcal.roundToInt()
                            nutrition.setTarget(
                                Macros(
                                    caloriesKcal = kcal.toDouble(),
                                    proteinGrams = (kcal * 0.3 / 4).roundToInt().toDouble(),
                                    carbsGrams = (kcal * 0.4 / 4).roundToInt().toDouble(),
                                    fatGrams = (kcal * 0.3 / 9).roundToInt().toDouble(),
                                ),
                            )
                        }
                    }
                }
            }
            // Reload so the reconciled flag drops out (natural "done" feedback).
            load()
        }
    }
}
