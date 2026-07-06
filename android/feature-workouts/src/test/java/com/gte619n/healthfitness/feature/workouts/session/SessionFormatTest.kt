package com.gte619n.healthfitness.feature.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.feature.workouts.program.ProgramFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionFormatTest {

    private val squat = ProgramFixtures.activeDraft.sessionSteps()[0].prescription
    private val plank = ProgramFixtures.activeDraft.sessionSteps()[1].prescription

    @Test
    fun `prefill carries the last logged set within the session first`() {
        val prefill = prefillFor(
            squat,
            logged = listOf(LoggedSet(weightLbs = 135.0, reps = 8)),
            lastSets = mapOf("ex-squat" to listOf(LoggedSet(weightLbs = 200.0, reps = 5))),
        )
        assertEquals(135.0, prefill.weightLbs)
        assertEquals(8, prefill.reps)
    }

    @Test
    fun `prefill falls back to the last session, then the designed target`() {
        // No in-session carry -> the literal previous session (200 x 5).
        val fromHistory = prefillFor(
            squat,
            logged = emptyList(),
            lastSets = mapOf("ex-squat" to listOf(LoggedSet(weightLbs = 200.0, reps = 5))),
        )
        assertEquals(200.0, fromHistory.weightLbs)
        assertEquals(5, fromHistory.reps)

        // No history either -> the designed reps target (fixture squat has no
        // target weight, so weight stays null).
        val fromTarget = prefillFor(squat, logged = emptyList(), lastSets = emptyMap())
        assertNull(fromTarget.weightLbs)
        assertEquals(10, fromTarget.reps)
    }

    @Test
    fun `prefill for a timed exercise carries the held duration, not weight`() {
        val prefill = prefillFor(plank, logged = emptyList(), lastSets = emptyMap())
        assertEquals(45, prefill.durationSeconds)
        assertNull(prefill.weightLbs)
        assertNull(prefill.reps)
    }

    @Test
    fun `announcement includes the effective load and reps`() {
        assertEquals(
            "Back Squat. 200 pounds, 5 reps.",
            coachAnnouncement(squat, weightLbs = 200.0, reps = 5),
        )
        // No weight known -> reps only; a timed hold speaks its duration.
        assertEquals("Back Squat. 10 reps.", coachAnnouncement(squat, weightLbs = null, reps = 10))
        assertEquals("Plank. 45 second hold.", coachAnnouncement(plank))
    }

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
