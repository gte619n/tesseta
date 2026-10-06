package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parity with Android's SessionFormatTest (workout-coach #297): the CROSS-session
 * reps fallback must be floored to the prescription's own band, while a
 * within-session carry stays literal. Prevents a freshly generated/refined program
 * (no engine rationale) from pre-rendering the pending reps red when the new band
 * jumped above last session's actual reps.
 */
class SessionFormatTest {

    @Test
    fun crossSessionRepsFallbackIsFlooredToTheBand() {
        // No in-session carry, no engine rationale → fall back to last session's
        // 5 reps, but that is below the floor (repsMin = 8), so prefill reads 8.
        val rx = samplePrescription(repsMin = 8, repsMax = 10, targetWeightLbs = null)
        val prefill = prefillFor(
            rx,
            logged = emptyList(),
            lastSets = mapOf("ex-1" to listOf(LoggedSet(weightLbs = 200.0, reps = 5))),
        )
        assertEquals(8, prefill.reps)
    }

    @Test
    fun withinSessionRepCarryStaysLiteralEvenBelowTheBand() {
        // You literally just did 5 reps this session → carry 5 as-is (no floor).
        val rx = samplePrescription(repsMin = 8, repsMax = 10, targetWeightLbs = null)
        val prefill = prefillFor(
            rx,
            logged = listOf(LoggedSet(weightLbs = 135.0, reps = 5)),
            lastSets = emptyMap(),
        )
        assertEquals(5, prefill.reps)
    }
}
