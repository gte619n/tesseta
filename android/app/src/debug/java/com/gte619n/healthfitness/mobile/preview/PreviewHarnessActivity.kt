package com.gte619n.healthfitness.mobile.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.workouts.program.ProgramSource
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.domain.workouts.progression.EnergyBalance
import com.gte619n.healthfitness.domain.workouts.progression.ExerciseStrength
import com.gte619n.healthfitness.domain.workouts.progression.PatternReview
import com.gte619n.healthfitness.feature.goals.plan.PhaseGuidance
import com.gte619n.healthfitness.feature.goals.plan.PlanChain
import com.gte619n.healthfitness.feature.goals.plan.PlanCoherenceContent
import com.gte619n.healthfitness.feature.goals.plan.computeFlags
import com.gte619n.healthfitness.feature.workouts.program.ProgramsListScreen
import com.gte619n.healthfitness.feature.workouts.program.ProgramsListUiState
import com.gte619n.healthfitness.feature.workouts.progression.ProgressionConsoleScreen
import com.gte619n.healthfitness.feature.workouts.progression.ProgressionConsoleViewModel
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.theme.Hf
import java.time.Instant
import java.time.LocalDate

/**
 * DEBUG-ONLY visual harness (never in release). Renders a single new/changed
 * screen with representative sample data so it can be screenshotted on an
 * emulator without a backend or sign-in. Pick the screen with:
 *
 *   adb shell am start -n com.gte619n.healthfitness/com.gte619n.healthfitness.mobile.preview.PreviewHarnessActivity \
 *       --es screen progression|coherence|programs
 */
class PreviewHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "progression"
        setContent {
            HealthFitnessTheme {
                when (screen) {
                    "coherence" -> CoherenceHarness()
                    "programs" -> ProgramsListScreen(
                        state = ProgramsListUiState(loading = false, programs = samplePrograms()),
                        onBack = {},
                        onOpenProgram = {},
                        onRetry = {},
                    )
                    else -> ProgressionConsoleScreen(state = progressionState(), onBack = {})
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CoherenceHarness() {
    val chain = PlanChain(
        goalTitle = "Drop to 12% body fat",
        goalDomain = "BODY_COMPOSITION",
        programTitle = "Hypertrophy Block — Fall",
        phaseTitle = "Phase 2 · Cut",
        guidance = PhaseGuidance(kcal = 2300, proteinG = 190, carbsG = 200, fatG = 70),
        target = Macros(caloriesKcal = 2750.0, proteinGrams = 170.0, carbsGrams = 300.0, fatGrams = 80.0),
        todayTotals = Macros(caloriesKcal = 1850.0, proteinGrams = 120.0, carbsGrams = 190.0, fatGrams = 60.0),
        energy = EnergyBalance(2680.0, 2740.0, 60.0, "RECOMP", true),
        blockMode = "GAINING",
        blockManualOverride = true,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
    ) {
        PlanCoherenceContent(chain = chain, flags = computeFlags(chain), pending = null, onReconcile = {})
    }
}

private fun progressionState() = ProgressionConsoleViewModel.State(
    loading = false,
    weekReview = listOf(
        PatternReview("PUSH_HORIZONTAL", "RISING", 2.4, 0.3, 12, 14, false, "Volume trending up with low fatigue — add a set."),
        PatternReview("PULL_VERTICAL", "FALLING", -1.1, 0.8, 14, 10, true, "Fatigue high and volume slipping — deload this pattern."),
    ),
    strength = listOf(
        ExerciseStrength("e1", "Conventional Deadlift", "HINGE", 405.0, "HIGH", 11),
        ExerciseStrength("e2", "Barbell Back Squat", "SQUAT", 315.0, "HIGH", 14),
        ExerciseStrength("e3", "Barbell Bench Press", "PUSH_HORIZONTAL", 245.0, "MEDIUM", 6),
        ExerciseStrength("e4", "Weighted Pull-Up", "PULL_VERTICAL", 90.0, "LOW", 3),
    ),
    energy = EnergyBalance(2680.0, 2740.0, 60.0, "RECOMP", true),
    goal = ProgressionConsoleViewModel.ActiveGoal("Drop to 12% body fat", "BODY_COMPOSITION"),
    block = BlockParameters(
        mode = "GAINING",
        expectedDriftPerDay = 0.1,
        successCriterion = "ADD_LOAD",
        manualOverride = true,
        repRanges = mapOf("PUSH_HORIZONTAL" to (6 to 10), "PULL_VERTICAL" to (8 to 12)),
        rirCaps = mapOf("COMPOUND" to 2.0, "ISOLATION" to 1.0),
        weeklyCeiling = mapOf("PUSH_HORIZONTAL" to 18, "PULL_VERTICAL" to 20),
    ),
)

private fun sampleProgram(
    id: String,
    title: String,
    status: ProgramStatus,
    weeks: Int = 12,
    phaseCount: Int = 4,
    completed: Int = 1,
) = WorkoutProgram(
    programId = id,
    title = title,
    description = "Periodized plan with deloads and gym-aware exercise selection.",
    goalId = "g1",
    status = status,
    source = ProgramSource.AI_GENERATED,
    startDate = LocalDate.of(2026, 8, 1),
    trainingDays = listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
    createdAt = Instant.parse("2026-07-20T00:00:00Z"),
    updatedAt = Instant.parse("2026-08-20T00:00:00Z"),
    totalWeeks = weeks,
    phaseCount = phaseCount,
    completedPhaseCount = completed,
)

private fun samplePrograms() = listOf(
    sampleProgram("a", "Hypertrophy Block — Fall", ProgramStatus.ACTIVE, completed = 2),
    sampleProgram("d1", "Strength Peak (draft)", ProgramStatus.DRAFT, completed = 0),
    sampleProgram("c1", "Summer Cut", ProgramStatus.COMPLETED, completed = 4),
    sampleProgram("ar", "2025 Powerbuilding", ProgramStatus.ARCHIVED, completed = 4),
)
