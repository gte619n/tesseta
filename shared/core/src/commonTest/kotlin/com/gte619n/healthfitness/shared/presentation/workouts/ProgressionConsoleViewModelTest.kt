package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.data.GoalRef
import com.gte619n.healthfitness.shared.domain.workouts.progression.EnergyBalance
import com.gte619n.healthfitness.shared.domain.workouts.progression.ExerciseStrength
import com.gte619n.healthfitness.shared.domain.workouts.progression.PatternReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The progression console's single-sourced presentation math: per-hand→total
 * (×2) strength reporting, load-trend formatting, the current→proposed set
 * string, the pinned-vs-measured divergence, and the required-vs-additive
 * failure behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressionConsoleViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(repo: FakeProgressionRepository, goals: FakeWorkoutGoalsRepository = FakeWorkoutGoalsRepository()) =
        ProgressionConsoleViewModel(repo, goals)

    @Test
    fun perHandDumbbellStrengthReportsTheTotalLifted() = runTest {
        val repo = FakeProgressionRepository(
            strengthResult = Result.success(
                listOf(
                    ExerciseStrength("e1", "Dumbbell Bench Press", "PUSH_HORIZONTAL", 45.0, "HIGH", 8),
                    ExerciseStrength("e2", "Barbell Back Squat", "SQUAT", 315.0, "HIGH", 12),
                ),
            ),
        )
        val v = vm(repo)
        advanceUntilIdle()

        val rows = v.state.value.strength
        val db = rows.first { it.exerciseId == "e1" }
        assertTrue(db.perHand, "a dumbbell lift is per-hand")
        assertEquals("90 lb", db.displayLoad, "45/hand reports as the 90 lb total")
        assertEquals("2 × 45 lb / hand", db.perHandCaption)

        val barbell = rows.first { it.exerciseId == "e2" }
        assertFalse(barbell.perHand, "a barbell lift is not per-hand")
        assertEquals("315 lb", barbell.displayLoad)
        assertNull(barbell.perHandCaption)
    }

    @Test
    fun weekReviewBakesTheCurrentToProposedSetTarget() = runTest {
        val repo = FakeProgressionRepository(
            review = Result.success(
                listOf(
                    PatternReview("PUSH_HORIZONTAL", "RISING", 2.4, 0.3, 12, 14, deload = false, reasoning = "add a set"),
                ),
            ),
        )
        val v = vm(repo)
        advanceUntilIdle()

        val row = v.state.value.weekReview.single()
        assertEquals("Push Horizontal", row.patternLabel)
        assertEquals("12 → 14", row.setTargetChange)
        assertFalse(row.deload)
    }

    @Test
    fun loadTrendFormatsSignAndPerWeekUnits() {
        assertEquals("+0.7 lb / week", ProgressionConsoleViewModel.formatLoadTrend(0.1))
        assertEquals("−1.4 lb / week", ProgressionConsoleViewModel.formatLoadTrend(-0.2))
        assertEquals("Holding — no planned change", ProgressionConsoleViewModel.formatLoadTrend(0.0))
    }

    @Test
    fun pinnedModeDivergesFromMeasuredEnergyBalance() = runTest {
        val repo = FakeProgressionRepository(
            block = Result.success(sampleBlock(mode = "GAINING", manualOverride = true)),
            energy = EnergyBalance(2680.0, 2200.0, -480.0, "MAINTENANCE", hasIntakeData = true),
        )
        val v = vm(repo)
        advanceUntilIdle()

        val s = v.state.value
        assertEquals("MAINTENANCE", s.measuredMode)
        assertTrue(s.pinnedDivergence, "pinned GAINING but measured MAINTENANCE → diverges")
    }

    @Test
    fun requiredReadFailureShowsErrorButAdditiveFailuresDegradeQuietly() = runTest {
        // Block (required) fails → error screen.
        val failed = FakeProgressionRepository(block = Result.failure(RuntimeException("offline")))
        val v1 = vm(failed)
        advanceUntilIdle()
        assertEquals("offline", v1.state.value.error)
        assertFalse(v1.state.value.loading)

        // Additive reads (strength/energy/goal) failing must NOT error the screen.
        val additiveFail = FakeProgressionRepository(strengthResult = Result.failure(RuntimeException("boom")))
        val v2 = vm(additiveFail)
        advanceUntilIdle()
        assertNull(v2.state.value.error)
        assertNotNull(v2.state.value.block)
        assertTrue(v2.state.value.strength.isEmpty())
    }

    @Test
    fun updateModePinsAndAdoptsTheReturnedBlock() = runTest {
        val repo = FakeProgressionRepository(updatedBlock = Result.success(sampleBlock(mode = "RECOMP", manualOverride = true)))
        val v = vm(repo)
        advanceUntilIdle()

        v.updateMode("RECOMP")
        advanceUntilIdle()

        assertEquals("RECOMP", repo.lastMode)
        assertEquals("RECOMP", v.state.value.block?.mode)
        assertFalse(v.state.value.updatingMode)
    }

    @Test
    fun activeGoalIsSurfacedForTheModeGoalLink() = runTest {
        val repo = FakeProgressionRepository()
        val goals = FakeWorkoutGoalsRepository(listOf(GoalRef("g1", "Drop to 12% body fat", "BODY_COMPOSITION")))
        val v = vm(repo, goals)
        advanceUntilIdle()

        assertEquals("Drop to 12% body fat", v.state.value.goal?.title)
        assertEquals("BODY_COMPOSITION", v.state.value.goal?.domain)
    }
}
