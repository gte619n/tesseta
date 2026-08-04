package com.gte619n.healthfitness.data.workouts.session

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.BlockType
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The client-side fold that backs the logger's offline "same as last time"
 * prefill — parity with the backend digest, so a session that isn't yet
 * persisted server-side still carries its previous weights over.
 */
class LastSessionSetsTest {

    @Test
    fun `takes the sets from the most recent completed session of each exercise`() {
        val old = completed(day(10), rx("bench", set(100.0, 5, at(10))))
        val recent = completed(
            day(3),
            rx("bench", set(135.0, 5, at(3)), set(140.0, 5, at(3).plusSeconds(90))),
        )

        val out = lastSessionSets(listOf(old, recent))

        assertEquals(listOf(135.0, 140.0), out.getValue("bench").map { it.weightLbs })
    }

    @Test
    fun `ignores sessions that are not completed`() {
        val done = completed(day(5), rx("bench", set(100.0, 5, at(5))))
        // A newer PLANNED session must not shadow the last COMPLETED one.
        val plannedNewer =
            completed(day(1), rx("bench", set(999.0, 5, at(1))), status = ScheduledStatus.PLANNED)

        val out = lastSessionSets(listOf(done, plannedNewer))

        assertEquals(listOf(100.0), out.getValue("bench").map { it.weightLbs })
    }

    @Test
    fun `orders the last session's sets by completedAt`() {
        val session = completed(
            day(2),
            rx("bench", set(140.0, 5, at(2).plusSeconds(120)), set(100.0, 5, at(2))),
        )

        val out = lastSessionSets(listOf(session))

        assertEquals(listOf(100.0, 140.0), out.getValue("bench").map { it.weightLbs })
    }

    @Test
    fun `an exercise with no history is absent`() {
        val out = lastSessionSets(listOf(completed(day(2), rx("bench", set(100.0, 5, at(2))))))

        assertNull(out["squat"])
        assertEquals(setOf("bench"), out.keys)
    }

    @Test
    fun `matches each exercise independently across days`() {
        // Bench last done 3 days ago; squat last done 8 days ago — each resolves
        // to its own most-recent completed session (the Program A/B/C case).
        val sessions = listOf(
            completed(day(8), rx("squat", set(225.0, 5, at(8)))),
            completed(day(3), rx("bench", set(135.0, 5, at(3)))),
        )

        val out = lastSessionSets(sessions)

        assertEquals(listOf(225.0), out.getValue("squat").map { it.weightLbs })
        assertEquals(listOf(135.0), out.getValue("bench").map { it.weightLbs })
    }

    // --- fixtures ---

    private fun day(daysAgo: Long): LocalDate = LocalDate.now().minusDays(daysAgo)

    private fun at(daysAgo: Long): Instant =
        day(daysAgo).atTime(18, 0).toInstant(ZoneOffset.UTC)

    private fun set(weight: Double, reps: Int, at: Instant): LoggedSet =
        LoggedSet(weightLbs = weight, reps = reps, completedAt = at)

    private fun rx(exerciseId: String, vararg sets: LoggedSet): Prescription =
        Prescription(
            exerciseId = exerciseId,
            orderIndex = 0,
            sets = null,
            repsMin = null,
            repsMax = null,
            durationSeconds = null,
            intensity = null,
            restSeconds = null,
            tempo = null,
            notes = null,
            deloadModifier = null,
            exercise = null,
            loggedSets = sets.toList(),
        )

    private fun completed(
        date: LocalDate,
        vararg prescriptions: Prescription,
        status: ScheduledStatus = ScheduledStatus.COMPLETED,
    ): ScheduledWorkout = ScheduledWorkout(
        scheduledId = "${date}_d",
        date = date,
        phaseId = "ph",
        dayId = "d",
        dayLabel = "Day",
        weekIndexInPhase = 1,
        isDeload = false,
        locationId = "loc",
        locationName = null,
        status = status,
        session = WorkoutDay(
            dayId = "d",
            label = "Day",
            dayOfWeek = DayOfWeek.MON,
            locationId = "loc",
            locationName = null,
            orderIndex = 0,
            blocks = listOf(Block("b", BlockType.MAIN, "Main", 0, prescriptions.toList())),
        ),
    )
}
