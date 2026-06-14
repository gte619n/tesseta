package com.gte619n.healthfitness.mobile.workouts

import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers.RestTimer
import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.BlockType
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.session.DraftStatus
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * ADR-0012 D6 — the pure notification-content derivation behind
 * [WorkoutSessionService]: current-exercise selection, set-count text, and the
 * elapsed/rest chronometer anchors. The service itself is glue and is not
 * unit-tested here.
 */
class WorkoutSessionNotificationContentTest {

    private val startedAt = Instant.parse("2026-06-10T10:00:00Z")
    private val now = Instant.parse("2026-06-10T10:30:00Z")

    @Test
    fun `current exercise is the first prescription when nothing is logged`() {
        val draft = draft()

        assertEquals("Air Bike", WorkoutSessionNotificationContent.currentExerciseName(draft))
    }

    @Test
    fun `current exercise advances past a fully-logged prescription`() {
        // Warmup done, bench's 3 prescribed sets all logged -> the row is next.
        val draft = draft(
            logged = mapOf(
                PrescriptionKey("b-warmup", 0) to listOf(loggedSet()),
                PrescriptionKey("b-main", 0) to List(3) { loggedSet() },
            ),
        )

        assertEquals("Barbell Row", WorkoutSessionNotificationContent.currentExerciseName(draft))
    }

    @Test
    fun `partially-logged prescription is still the current exercise`() {
        val draft = draft(
            logged = mapOf(
                PrescriptionKey("b-warmup", 0) to listOf(loggedSet()),
                PrescriptionKey("b-main", 0) to List(2) { loggedSet() },
            ),
        )

        assertEquals("Bench Press", WorkoutSessionNotificationContent.currentExerciseName(draft))
    }

    @Test
    fun `null prescribed sets counts as a single set`() {
        // The warmup row has sets=null: one logged set completes it.
        val draft = draft(
            logged = mapOf(PrescriptionKey("b-warmup", 0) to listOf(loggedSet())),
        )

        assertEquals("Bench Press", WorkoutSessionNotificationContent.currentExerciseName(draft))
    }

    @Test
    fun `blocks and prescriptions are walked in orderIndex order, not list order`() {
        // Same day, but with the block/prescription lists shuffled.
        val day = day().let { d ->
            d.copy(
                blocks = d.blocks.reversed().map { block ->
                    block.copy(prescriptions = block.prescriptions.reversed())
                },
            )
        }
        val draft = draft(scheduled = scheduled(session = day))

        // Still the warmup (block orderIndex 0, prescription orderIndex 0) first.
        assertEquals("Air Bike", WorkoutSessionNotificationContent.currentExerciseName(draft))
    }

    @Test
    fun `exercise name falls back to the exerciseId when the summary is missing`() {
        val day = day().let { d ->
            d.copy(
                blocks = d.blocks.map { block ->
                    block.copy(prescriptions = block.prescriptions.map { it.copy(exercise = null) })
                },
            )
        }
        val draft = draft(scheduled = scheduled(session = day))

        assertEquals("ex-bike", WorkoutSessionNotificationContent.currentExerciseName(draft))
    }

    @Test
    fun `current exercise is null once everything is logged — and with no snapshot`() {
        assertNull(WorkoutSessionNotificationContent.currentExerciseName(draft(logged = allLogged())))
        assertNull(
            WorkoutSessionNotificationContent.currentExerciseName(
                draft(scheduled = scheduled(session = null)),
            ),
        )
    }

    @Test
    fun `workout mode anchors a count-up chronometer at the session start`() {
        val content = WorkoutSessionNotificationContent.from(draft(), rest = null, now = now)

        assertEquals("Push Day", content.title)
        assertEquals("Now: Air Bike · 0 sets logged", content.text)
        assertEquals(startedAt.toEpochMilli(), content.elapsedSinceMillis)
        assertNull(content.countdownToMillis)
    }

    @Test
    fun `set-count text pluralizes`() {
        assertEquals("0 sets logged", WorkoutSessionNotificationContent.setsLoggedLabel(0))
        assertEquals("1 set logged", WorkoutSessionNotificationContent.setsLoggedLabel(1))
        assertEquals("4 sets logged", WorkoutSessionNotificationContent.setsLoggedLabel(4))
    }

    @Test
    fun `fully-logged session prompts the user to finish`() {
        val content =
            WorkoutSessionNotificationContent.from(draft(logged = allLogged()), rest = null, now = now)

        assertEquals("All sets logged — finish when ready", content.text)
        assertEquals(startedAt.toEpochMilli(), content.elapsedSinceMillis)
    }

    @Test
    fun `running rest timer switches to a countdown anchored at its end`() {
        val rest = RestTimer(totalSeconds = 90, endsAt = now.plusSeconds(45))

        val content = WorkoutSessionNotificationContent.from(draft(), rest = rest, now = now)

        assertEquals("Resting — next: Air Bike", content.text)
        assertEquals(rest.endsAt.toEpochMilli(), content.countdownToMillis)
        assertNull(content.elapsedSinceMillis)
    }

    @Test
    fun `expired rest timer falls back to workout mode`() {
        val rest = RestTimer(totalSeconds = 90, endsAt = now.minusSeconds(1))

        val content = WorkoutSessionNotificationContent.from(draft(), rest = rest, now = now)

        assertEquals("Now: Air Bike · 0 sets logged", content.text)
        assertEquals(startedAt.toEpochMilli(), content.elapsedSinceMillis)
        assertNull(content.countdownToMillis)
    }

    // ---- fixtures ----------------------------------------------------------

    private fun loggedSet() = LoggedSet(weightLbs = 135.0, reps = 8)

    /** All prescriptions logged to their prescribed set counts. */
    private fun allLogged(): Map<PrescriptionKey, List<LoggedSet>> = mapOf(
        PrescriptionKey("b-warmup", 0) to listOf(loggedSet()),
        PrescriptionKey("b-main", 0) to List(3) { loggedSet() },
        PrescriptionKey("b-main", 1) to List(3) { loggedSet() },
    )

    private fun prescription(exerciseId: String, name: String?, orderIndex: Int, sets: Int?) =
        Prescription(
            exerciseId = exerciseId,
            orderIndex = orderIndex,
            sets = sets,
            repsMin = null,
            repsMax = null,
            durationSeconds = null,
            intensity = null,
            restSeconds = null,
            tempo = null,
            notes = null,
            deloadModifier = null,
            exercise = name?.let {
                ExerciseSummary(exerciseId, it, emptyList(), emptyList(), emptyList())
            },
        )

    /** Warmup block (Air Bike, sets=null) then main block (Bench x3, Row x3). */
    private fun day() = WorkoutDay(
        dayId = "day-1",
        label = "Push Day",
        dayOfWeek = DayOfWeek.MON,
        locationId = "gym-1",
        locationName = null,
        orderIndex = 0,
        blocks = listOf(
            Block(
                blockId = "b-warmup",
                type = BlockType.WARMUP,
                title = "Warmup",
                orderIndex = 0,
                prescriptions = listOf(prescription("ex-bike", "Air Bike", 0, sets = null)),
            ),
            Block(
                blockId = "b-main",
                type = BlockType.MAIN,
                title = "Main",
                orderIndex = 1,
                prescriptions = listOf(
                    prescription("ex-bench", "Bench Press", 0, sets = 3),
                    prescription("ex-row", "Barbell Row", 1, sets = 3),
                ),
            ),
        ),
    )

    private fun scheduled(session: WorkoutDay? = day()) = ScheduledWorkout(
        scheduledId = "2026-06-10_day-1",
        date = LocalDate.parse("2026-06-10"),
        phaseId = "phase-1",
        dayId = "day-1",
        dayLabel = "Push Day",
        weekIndexInPhase = 0,
        isDeload = false,
        locationId = "gym-1",
        locationName = null,
        status = ScheduledStatus.PLANNED,
        session = session,
    )

    private fun draft(
        scheduled: ScheduledWorkout = scheduled(),
        logged: Map<PrescriptionKey, List<LoggedSet>> = emptyMap(),
    ) = WorkoutSessionDraft(
        programId = "prog-1",
        scheduledId = scheduled.scheduledId,
        startedAt = startedAt,
        lastActivityAt = startedAt,
        status = DraftStatus.ACTIVE,
        scheduled = scheduled,
        logged = logged,
    )
}
