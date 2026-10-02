package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.data.resumeDayLoads
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #283 redo-a-day: an offline redo resumes the user's last progressed LOAD while
 * keeping the template's rep/set targets. Pure → runs on JVM + iOS.
 * (Reuses FakeWorkoutData's sample* builders.)
 */
class RedoDayLoadsTest {

    private fun completedWith(
        date: LocalDate,
        prescriptions: List<com.gte619n.healthfitness.shared.domain.workouts.program.Prescription>,
    ) = sampleScheduled("s-$date", date, ScheduledStatus.COMPLETED)
        .copy(session = sampleDay(prescriptions = prescriptions))

    @Test
    fun resumesLoadFromNewestCompletedButKeepsTemplateRepsAndSets() {
        // Template (author week-one): squat 5x5 @ 135.
        val template = sampleDay(
            prescriptions = listOf(samplePrescription("squat", sets = 5, repsMin = 5, repsMax = 5, targetWeightLbs = 135.0)),
        )
        // History: older 5x5@185, newest 3x8-10@225 — newest load should win.
        val completed = listOf(
            completedWith(LocalDate(2026, 9, 1), listOf(samplePrescription("squat", sets = 5, repsMin = 5, repsMax = 5, targetWeightLbs = 185.0))),
            completedWith(LocalDate(2026, 9, 8), listOf(samplePrescription("squat", sets = 3, repsMin = 8, repsMax = 10, targetWeightLbs = 225.0))),
        )

        val rx = resumeDayLoads(template, completed).blocks.first().prescriptions.first()
        assertEquals(225.0, rx.targetWeightLbs)   // newest real load resumed
        assertEquals(5, rx.sets)                  // template volume kept
        assertEquals(5, rx.repsMin)
        assertEquals(5, rx.repsMax)
    }

    @Test
    fun exerciseWithNoHistoryIsUntouched() {
        val template = sampleDay(
            prescriptions = listOf(samplePrescription("bench", sets = 3, targetWeightLbs = 95.0)),
        )
        val completed = listOf(
            completedWith(LocalDate(2026, 9, 8), listOf(samplePrescription("squat", targetWeightLbs = 225.0))),
        )
        val rx = resumeDayLoads(template, completed).blocks.first().prescriptions.first()
        assertEquals(95.0, rx.targetWeightLbs)   // no squat→bench bleed; template load kept
    }

    @Test
    fun noCompletedHistoryReturnsTemplateUnchanged() {
        val template = sampleDay(prescriptions = listOf(samplePrescription("squat", targetWeightLbs = 135.0)))
        assertEquals(template, resumeDayLoads(template, emptyList()))
        // A PLANNED (not COMPLETED) session is ignored.
        val planned = sampleScheduled("s1", LocalDate(2026, 9, 8), ScheduledStatus.PLANNED)
            .copy(session = sampleDay(prescriptions = listOf(samplePrescription("squat", targetWeightLbs = 999.0))))
        assertEquals(template, resumeDayLoads(template, listOf(planned)))
    }
}
