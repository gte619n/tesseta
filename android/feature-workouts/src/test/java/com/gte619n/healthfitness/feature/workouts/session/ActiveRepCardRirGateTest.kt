package com.gte619n.healthfitness.feature.workouts.session

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * IMPL-PROG-02 F4/D4: the final working set cannot be logged until the athlete
 * explicitly reports RIR. Robolectric-backed Compose UI test (no device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveRepCardRirGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val prescription = Prescription(
        exerciseId = "ex-bench",
        orderIndex = 0,
        sets = 3,
        repsMin = 6,
        repsMax = 10,
        durationSeconds = null,
        intensity = null,
        restSeconds = null,
        tempo = null,
        notes = null,
        deloadModifier = null,
        exercise = ExerciseSummary("ex-bench", "Bench Press", emptyList(), emptyList(), emptyList()),
    )

    @Test
    fun `final set cannot be logged until RIR is explicitly picked`() {
        var logged: Triple<Double?, Int?, Double?>? = null
        composeRule.setContent {
            ActiveRepCard(
                setNumber = 3,
                totalSets = 3,
                prefill = SetPrefill(weightLbs = 100.0, reps = 8),
                prescription = prescription,
                exerciseName = "Bench Press",
                onLog = { w, r, rir -> logged = Triple(w, r, rir) },
                requireRir = true,
            )
        }

        // Gate closed: the button reads "Pick reps in reserve" and is disabled.
        composeRule.onNodeWithText("Pick reps in reserve").assertIsNotEnabled()
        assertNull(logged)

        // Explicitly report RIR = 2 → the gate opens.
        composeRule.onNodeWithText("2").performClick()
        composeRule.onNodeWithText("Log set 3").assertIsEnabled().performClick()

        // Logged with the reported RIR.
        assertEquals(2.0, logged?.third)
        assertEquals(100.0, logged?.first)
        assertEquals(8, logged?.second)
    }

    @Test
    fun `a non-final set logs immediately with no RIR gate`() {
        var logged: Triple<Double?, Int?, Double?>? = null
        composeRule.setContent {
            ActiveRepCard(
                setNumber = 1,
                totalSets = 3,
                prefill = SetPrefill(weightLbs = 100.0, reps = 8),
                prescription = prescription,
                exerciseName = "Bench Press",
                onLog = { w, r, rir -> logged = Triple(w, r, rir) },
                requireRir = false,
            )
        }

        // No RIR prompt; logging works straight away, RIR null.
        composeRule.onNodeWithText("Log set 1").assertIsEnabled().performClick()
        assertEquals(100.0, logged?.first)
        assertNull(logged?.third)
    }
}
