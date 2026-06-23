package com.gte619n.healthfitness.feature.workouts.session

import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.feature.workouts.program.ProgramFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFormatTest {

    @Test
    fun `elapsed label is mm-ss under an hour and h-mm-ss above`() {
        assertEquals("0:00", elapsedLabel(0))
        assertEquals("0:05", elapsedLabel(5))
        assertEquals("47:32", elapsedLabel(47 * 60 + 32))
        assertEquals("1:02:10", elapsedLabel(3600 + 2 * 60 + 10))
        assertEquals("0:00", elapsedLabel(-3))
    }

    @Test
    fun `rest countdown label is mm-ss clamped at zero`() {
        assertEquals("1:30", restCountdownLabel(90))
        assertEquals("0:09", restCountdownLabel(9))
        assertEquals("0:00", restCountdownLabel(-1))
    }

    @Test
    fun `session steps flatten blocks then prescriptions in order`() {
        val steps = ProgramFixtures.activeDraft.sessionSteps()
        // The fixture day is main (squat) then core (plank).
        assertEquals(2, steps.size)
        assertEquals(PrescriptionKey("b-main", 0), steps[0].key)
        assertEquals("ex-squat", steps[0].prescription.exerciseId)
        assertEquals(PrescriptionKey("b-core", 0), steps[1].key)
        assertEquals("ex-plank", steps[1].prescription.exerciseId)
    }

    @Test
    fun `first incomplete step skips fully logged exercises`() {
        // Squat has 1 of 3 sets logged, so the coach opens on it.
        assertEquals(0, ProgramFixtures.activeDraft.firstIncompleteStepIndex())

        // Once the squat's 3 sets are all logged, the plank (index 1) is next.
        val squatDone = ProgramFixtures.activeDraft.copy(
            logged = mapOf(
                PrescriptionKey("b-main", 0) to List(3) {
                    com.gte619n.healthfitness.domain.workouts.program.LoggedSet(weightLbs = 185.0, reps = 8)
                },
            ),
        )
        assertEquals(1, squatDone.firstIncompleteStepIndex())
    }

    @Test
    fun `logged exercise counts cover all prescriptions in the snapshot`() {
        // The fixture day has two prescriptions (squat + plank); one is logged.
        assertEquals(1 to 2, loggedExerciseCounts(ProgramFixtures.activeDraft))

        val none = ProgramFixtures.activeDraft.copy(logged = emptyMap())
        assertEquals(0 to 2, loggedExerciseCounts(none))

        val noSession = ProgramFixtures.activeDraft.copy(
            scheduled = ProgramFixtures.activeDraft.scheduled.copy(session = null),
            logged = mapOf(PrescriptionKey("b-main", 0) to emptyList()),
        )
        assertEquals(0 to 0, loggedExerciseCounts(noSession))
    }
}
