package com.gte619n.healthfitness.feature.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.PrescriptionRationale
import com.gte619n.healthfitness.domain.workouts.program.ProgressionConfidence
import com.gte619n.healthfitness.domain.workouts.program.ProgressionDirection
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.feature.workouts.program.ProgramFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFormatTest {

    private val squat = ProgramFixtures.activeDraft.sessionSteps()[0].prescription
    private val plank = ProgramFixtures.activeDraft.sessionSteps()[1].prescription

    // ---- IMPL-PROG-02 fixtures & helpers ----

    private fun rationale(
        direction: ProgressionDirection,
        deltaLbs: Double? = null,
    ) = PrescriptionRationale(
        path = "KALMAN",
        direction = direction,
        deltaLbs = deltaLbs,
        deltaReps = null,
        deltaSets = null,
        confidence = ProgressionConfidence.HIGH,
        inputs = listOf("last: 100×8"),
    )

    private fun rx(
        exerciseId: String = "ex-x",
        name: String? = "Cable Push-down",
        repsMin: Int? = 8,
        repsMax: Int? = 12,
        targetWeightLbs: Double? = null,
        rationale: PrescriptionRationale? = null,
        isBodyweight: Boolean = false,
    ) = Prescription(
        exerciseId = exerciseId,
        orderIndex = 0,
        sets = 3,
        repsMin = repsMin,
        repsMax = repsMax,
        durationSeconds = null,
        intensity = null,
        restSeconds = null,
        tempo = null,
        notes = null,
        deloadModifier = null,
        exercise = name?.let { ExerciseSummary(exerciseId, it, emptyList(), emptyList(), emptyList()) },
        targetWeightLbs = targetWeightLbs,
        rationale = rationale,
        isBodyweight = isBodyweight,
    )

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
    fun `prefill anchors on the last set of the previous session, not the first`() {
        // Last session ramped 100x10 -> 160x8. Opening fresh, every pending set
        // anchors on the final (heaviest) set, not the warm-up first set.
        val prefill = prefillFor(
            squat,
            logged = emptyList(),
            lastSets = mapOf(
                "ex-squat" to listOf(
                    LoggedSet(weightLbs = 100.0, reps = 10),
                    LoggedSet(weightLbs = 160.0, reps = 8),
                ),
            ),
        )
        assertEquals(160.0, prefill.weightLbs)
        assertEquals(8, prefill.reps)
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
    fun `get ready announcement names the upcoming hold and its duration`() {
        assertEquals("Get ready for Plank. 45 second hold.", getReadyAnnouncement(plank))
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
    fun `resume opens at the furthest exercise, not a skipped earlier one`() {
        // Nothing logged yet -> open at the top.
        assertEquals(0, ProgramFixtures.activeDraft.copy(logged = emptyMap()).resumeStepIndex())

        // The fixture is squat (index 0, 3 sets) then plank (index 1). Simulate
        // passing the squat without logging it (via "Next") and starting the
        // plank: resume must stay on the plank, not snap back to the squat.
        val squatSkippedPlankStarted = ProgramFixtures.activeDraft.copy(
            logged = mapOf(
                PrescriptionKey("b-core", 0) to listOf(LoggedSet(durationSeconds = 45)),
            ),
        )
        assertEquals(1, squatSkippedPlankStarted.resumeStepIndex())
        // firstIncompleteStepIndex is the buggy behaviour we moved away from: it
        // would drag focus back to the unlogged squat.
        assertEquals(0, squatSkippedPlankStarted.firstIncompleteStepIndex())

        // Mid-way through the furthest exercise -> resume on it.
        val plankMidway = ProgramFixtures.activeDraft.copy(
            logged = mapOf(
                PrescriptionKey("b-main", 0) to List(3) { LoggedSet(weightLbs = 185.0, reps = 8) },
                PrescriptionKey("b-core", 0) to listOf(LoggedSet(durationSeconds = 45)),
            ),
        )
        assertEquals(1, plankMidway.resumeStepIndex())
    }

    @Test
    fun `session is complete only when every prescribed set is logged`() {
        val draft = ProgramFixtures.activeDraft
        // The fixture has squat (1 of 3) and plank (0 of 3) -> not complete.
        assertFalse(draft.isComplete())

        // All three of each prescription's sets logged -> complete.
        val allDone = draft.copy(
            logged = mapOf(
                PrescriptionKey("b-main", 0) to List(3) { LoggedSet(weightLbs = 185.0, reps = 8) },
                PrescriptionKey("b-core", 0) to List(3) { LoggedSet(durationSeconds = 45) },
            ),
        )
        assertTrue(allDone.isComplete())

        // The projected overload sees the map about to be persisted, so the last
        // set completes the session before Room echoes it back.
        val oneLeft = draft.copy(
            logged = mapOf(
                PrescriptionKey("b-main", 0) to List(3) { LoggedSet(weightLbs = 185.0, reps = 8) },
                PrescriptionKey("b-core", 0) to List(2) { LoggedSet(durationSeconds = 45) },
            ),
        )
        assertFalse(oneLeft.isComplete())
        assertTrue(
            oneLeft.isComplete(
                oneLeft.logged + (PrescriptionKey("b-core", 0) to List(3) { LoggedSet(durationSeconds = 45) }),
            ),
        )
    }

    @Test
    fun `reps outcome is a hit at or above the range floor, a miss below`() {
        // Squat range is 8-10.
        assertEquals(TargetOutcome.HIT, repsOutcome(squat, 8))
        assertEquals(TargetOutcome.HIT, repsOutcome(squat, 12))
        assertEquals(TargetOutcome.MISS, repsOutcome(squat, 7))
        assertEquals(TargetOutcome.NEUTRAL, repsOutcome(squat, null))
        // A timed exercise has no rep range -> nothing to compare.
        assertEquals(TargetOutcome.NEUTRAL, repsOutcome(plank, 5))
    }

    @Test
    fun `weight outcome compares against the target load when one exists`() {
        val loaded = squat.copy(targetWeightLbs = 185.0)
        assertEquals(TargetOutcome.HIT, weightOutcome(loaded, 185.0))
        assertEquals(TargetOutcome.HIT, weightOutcome(loaded, 200.0))
        assertEquals(TargetOutcome.MISS, weightOutcome(loaded, 180.0))
        assertEquals(TargetOutcome.NEUTRAL, weightOutcome(loaded, null))
        // No target load on the base squat fixture -> nothing to compare.
        assertEquals(TargetOutcome.NEUTRAL, weightOutcome(squat, 185.0))
    }

    @Test
    fun `rest announcement phrases minutes and seconds`() {
        assertEquals("Rest 45 seconds.", restAnnouncement(45))
        assertEquals("Rest 1 minute 30 seconds.", restAnnouncement(90))
        assertEquals("Rest 2 minutes.", restAnnouncement(120))
        assertEquals("Rest 1 minute.", restAnnouncement(60))
    }

    // ---- IMPL-PROG-02 F2/F3/D1: prediction is authoritative over last-session actual ----

    @Test
    fun `prefill prefers the engine prediction over last-session actual`() {
        val prescribed = rx(targetWeightLbs = 155.0, rationale = rationale(ProgressionDirection.UP))
        val prefill = prefillFor(
            prescribed,
            logged = emptyList(),
            lastSets = mapOf("ex-x" to listOf(LoggedSet(weightLbs = 145.0, reps = 12))),
        )
        // The prediction (155) wins over what was lifted last time (145) — no race.
        assertEquals(155.0, prefill.weightLbs)
    }

    @Test
    fun `prefill still carries last session when there is no prediction`() {
        // A static prescription (no target, no rationale) keeps carry-forward.
        val prefill = prefillFor(
            rx(name = "Row", targetWeightLbs = null, rationale = null),
            logged = emptyList(),
            lastSets = mapOf("ex-x" to listOf(LoggedSet(weightLbs = 95.0, reps = 10))),
        )
        assertEquals(95.0, prefill.weightLbs)
    }

    // ---- IMPL-PROG-02 F5/D5: reps reset to the band bottom on a weight increase ----

    @Test
    fun `target reps reset to band bottom on a weight increase`() {
        val up = rx(repsMin = 6, repsMax = 10, rationale = rationale(ProgressionDirection.UP))
        assertEquals(6, targetReps(up))
    }

    @Test
    fun `target reps hold at band top when not increasing`() {
        val hold = rx(repsMin = 6, repsMax = 10, rationale = rationale(ProgressionDirection.HOLD))
        assertEquals(10, targetReps(hold))
    }

    @Test
    fun `target reps are null without an engine decision`() {
        assertNull(targetReps(rx(rationale = null)))
    }

    // ---- IMPL-PROG-02 F6: "body weight" only for real bodyweight movements ----

    @Test
    fun `bodyweight movement announces body weight`() {
        val dip = rx(name = "Dip", repsMax = 8, isBodyweight = true, targetWeightLbs = null)
        assertEquals("Dip. body weight, 8 reps.", coachAnnouncement(dip))
    }

    @Test
    fun `first-time weighted lift announces its seed load, never body weight`() {
        // The cable push-down regression: seeded to 45 lb, must speak a number.
        val pushdown = rx(name = "Cable Push-down", repsMax = 12, targetWeightLbs = 45.0)
        val spoken = coachAnnouncement(pushdown)
        assertEquals("Cable Push-down. 45 pounds, 12 reps.", spoken)
        assertFalse(spoken!!.contains("body weight"))
    }

    @Test
    fun `non-bodyweight lift with no known load omits the weight, not body weight`() {
        val unknown = rx(name = "Cable Push-down", repsMax = 12, targetWeightLbs = null)
        val spoken = coachAnnouncement(unknown, weightLbs = 0.0)
        assertFalse(spoken!!.contains("body weight"))
        assertEquals("Cable Push-down. 12 reps.", spoken)
    }

    // ---- IMPL-PROG-02 F1/D2/D9: adjustment chip vs last time ----

    @Test
    fun `weight adjustment reports the delta versus last time`() {
        val prescribed = rx(targetWeightLbs = 155.0, rationale = rationale(ProgressionDirection.UP))
        val adj = weightAdjustment(prescribed, mapOf("ex-x" to listOf(LoggedSet(weightLbs = 145.0, reps = 12))))
        assertEquals(true, adj?.up)
        assertEquals("+10 lb", adj?.label)
    }

    @Test
    fun `no weight adjustment chip without an engine decision`() {
        val prescribed = rx(targetWeightLbs = 155.0, rationale = null)
        assertNull(weightAdjustment(prescribed, mapOf("ex-x" to listOf(LoggedSet(weightLbs = 145.0, reps = 12)))))
    }

    @Test
    fun `reps adjustment reports the delta when the engine reset the band`() {
        val up = rx(repsMin = 6, repsMax = 10, rationale = rationale(ProgressionDirection.UP))
        val adj = repsAdjustment(up, mapOf("ex-x" to listOf(LoggedSet(weightLbs = 145.0, reps = 10))))
        // Target dropped from 10 (last) to 6 (band bottom) → −4 reps.
        assertEquals(false, adj?.up)
        assertEquals("−4 reps", adj?.label)
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
