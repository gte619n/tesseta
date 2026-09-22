package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.domain.workouts.program.Intensity
import com.gte619n.healthfitness.domain.workouts.program.IntensityKind
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.PrescriptionRationale
import com.gte619n.healthfitness.domain.workouts.program.ProgressionConfidence
import com.gte619n.healthfitness.domain.workouts.program.ProgressionDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProgramFormatTest {

    private fun rationale(direction: ProgressionDirection) = PrescriptionRationale(
        path = "KALMAN",
        direction = direction,
        confidence = ProgressionConfidence.HIGH,
    )

    private fun rx(
        sets: Int? = 3,
        repsMin: Int? = 8,
        repsMax: Int? = 10,
        durationSeconds: Int? = null,
        restSeconds: Int? = 90,
        intensity: Intensity? = Intensity(IntensityKind.RPE, 8.0),
        tempo: String? = "3010",
        targetWeightLbs: Double? = null,
        rationale: PrescriptionRationale? = null,
        isBodyweight: Boolean = false,
    ) = Prescription(
        exerciseId = "ex-x",
        orderIndex = 0,
        sets = sets,
        repsMin = repsMin,
        repsMax = repsMax,
        durationSeconds = durationSeconds,
        intensity = intensity,
        restSeconds = restSeconds,
        tempo = tempo,
        notes = null,
        deloadModifier = null,
        exercise = null,
        targetWeightLbs = targetWeightLbs,
        rationale = rationale,
        isBodyweight = isBodyweight,
    )

    // ---- fixedRepTarget: a single number, not a range ----

    @Test
    fun `fixed rep target is the band top without an engine decision`() {
        assertEquals(10, fixedRepTarget(rx(repsMin = 8, repsMax = 10, rationale = null)))
    }

    @Test
    fun `fixed rep target resets to the band bottom on a load increase`() {
        assertEquals(8, fixedRepTarget(rx(repsMin = 8, repsMax = 10, rationale = rationale(ProgressionDirection.UP))))
    }

    @Test
    fun `fixed rep target holds at the band top when not increasing`() {
        assertEquals(10, fixedRepTarget(rx(repsMin = 8, repsMax = 10, rationale = rationale(ProgressionDirection.HOLD))))
    }

    @Test
    fun `fixed rep target is null when no reps are prescribed`() {
        assertNull(fixedRepTarget(rx(repsMin = null, repsMax = null, durationSeconds = 45)))
    }

    // ---- prescriptionTargetLine: weight · sets × fixed-reps · rest, no RPE/tempo ----

    @Test
    fun `target line leads with load, a single rep target, and rest`() {
        assertEquals("155 lb · 3 × 10 · rest 90s", prescriptionTargetLine(rx(targetWeightLbs = 155.0)))
    }

    @Test
    fun `target line drops RPE and tempo entirely`() {
        val line = prescriptionTargetLine(rx(targetWeightLbs = 155.0))
        assertFalse(line.contains("RPE"))
        assertFalse(line.contains("tempo"))
    }

    @Test
    fun `target line reads BW for a bodyweight movement with no external load`() {
        // 20-rep bodyweight calf raise, resting 1m: "BW · 3 × 20 · rest 1m".
        val calf = rx(repsMin = 15, repsMax = 20, restSeconds = 60, targetWeightLbs = null, isBodyweight = true)
        assertEquals("BW · 3 × 20 · rest 1m", prescriptionTargetLine(calf))
    }

    @Test
    fun `target line shows the added load once a bodyweight movement is weighted`() {
        // After the first increment: reset to the high band bottom (15) at 5 lb, UP.
        val calf = rx(
            repsMin = 15, repsMax = 16, restSeconds = 60,
            targetWeightLbs = 5.0, isBodyweight = true,
            rationale = rationale(ProgressionDirection.UP),
        )
        assertEquals("5 lb · 3 × 15 · rest 1m", prescriptionTargetLine(calf))
    }

    @Test
    fun `target line for a timed hold reads its duration`() {
        val hold = rx(repsMin = null, repsMax = null, durationSeconds = 45, restSeconds = null)
        assertEquals("45s", prescriptionTargetLine(hold))
    }
}
