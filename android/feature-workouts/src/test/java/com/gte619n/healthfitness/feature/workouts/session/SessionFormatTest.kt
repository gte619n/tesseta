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
