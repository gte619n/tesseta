package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsLandingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: WorkoutProgramRepository = mockk()
    private val sessionRepo: WorkoutSessionRepository = mockk()
    private val drafts = MutableStateFlow<List<WorkoutSessionDraft>>(emptyList())
    private val parked = MutableStateFlow<List<ParkedCompletion>>(emptyList())

    private val today = LocalDate.parse("2026-06-10") // a Wednesday

    private fun program(id: String, status: ProgramStatus, updatedAt: String): WorkoutProgram =
        ProgramFixtures.deepProgram.copy(
            programId = id,
            status = status,
            updatedAt = Instant.parse(updatedAt),
            phases = emptyList(),
        )

    private fun sched(date: String, status: ScheduledStatus): ScheduledWorkout =
        ScheduledWorkout(
            scheduledId = "s-$date",
            date = LocalDate.parse(date),
            phaseId = "ph",
            dayId = "d",
            dayLabel = "Day",
            weekIndexInPhase = 1,
            isDeload = false,
            locationId = "g",
            locationName = null,
            status = status,
            programId = "p1",
        )

    private fun vm(
        programs: List<WorkoutProgram>,
        calendar: List<ScheduledWorkout> = emptyList(),
    ): WorkoutsLandingViewModel {
        every { sessionRepo.observeDrafts() } returns drafts
        every { sessionRepo.observeParkedCompletions() } returns parked
        every { repo.observePrograms() } returns flowOf(programs)
        every { repo.observeProgram(any()) } answers {
            val id = firstArg<String>()
            flowOf(programs.firstOrNull { it.programId == id })
        }
        every { repo.observeCalendar(any(), any(), any()) } returns flowOf(calendar)
        return WorkoutsLandingViewModel(repo, sessionRepo).also { it.today = today }
    }

    @Test
    fun `resolves the ACTIVE program as the featured one`() = runTest {
        val programs = listOf(
            program("draft", ProgramStatus.DRAFT, "2026-06-09T00:00:00Z"),
            program("active", ProgramStatus.ACTIVE, "2026-01-01T00:00:00Z"),
        )
        val vm = vm(programs)
        advanceUntilIdle()

        assertEquals("active", vm.state.value.program?.programId)
        assertTrue(vm.state.value.hasAnyProgram)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `falls back to the most recent program when none is active`() = runTest {
        val programs = listOf(
            program("old", ProgramStatus.COMPLETED, "2026-01-01T00:00:00Z"),
            program("new", ProgramStatus.DRAFT, "2026-06-09T00:00:00Z"),
        )
        val vm = vm(programs)
        advanceUntilIdle()

        assertEquals("new", vm.state.value.program?.programId)
    }

    @Test
    fun `no programs yields an empty landing`() = runTest {
        val vm = vm(emptyList())
        advanceUntilIdle()

        assertNull(vm.state.value.program)
        assertFalse(vm.state.value.hasAnyProgram)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `slices this week, month, past and streak from one calendar`() = runTest {
        val calendar = listOf(
            sched("2026-05-29", ScheduledStatus.COMPLETED), // previous month
            sched("2026-06-01", ScheduledStatus.COMPLETED),
            sched("2026-06-03", ScheduledStatus.COMPLETED),
            sched("2026-06-08", ScheduledStatus.COMPLETED), // this week (Mon)
            sched("2026-06-10", ScheduledStatus.COMPLETED), // this week (today)
            sched("2026-06-12", ScheduledStatus.PLANNED), // this week (future)
        )
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-05-01T00:00:00Z"))
        val vm = vm(programs, calendar)
        advanceUntilIdle()

        val state = vm.state.value
        // Mon 06-08 .. Sun 06-14 → three days.
        assertEquals(listOf("2026-06-08", "2026-06-10", "2026-06-12"), state.thisWeek.map { it.date.toString() })
        // June only (excludes 05-29).
        assertEquals(5, state.monthDays.size)
        assertTrue(state.monthDays.none { it.date.month.value == 5 })
        // On/before today, newest first.
        assertEquals("2026-06-10", state.pastSessions.first().date.toString())
        assertEquals(5, state.pastSessions.size)
        // Five consecutive completed scheduled days up to today.
        assertEquals(5, state.streak)
        assertEquals(YearMonth.of(2026, 6), state.visibleMonth)
    }

    @Test
    fun `next and previous month shift the visible month`() = runTest {
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-05-01T00:00:00Z"))
        val vm = vm(programs)
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 6), vm.state.value.visibleMonth)

        vm.nextMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 7), vm.state.value.visibleMonth)

        vm.prevMonth()
        vm.prevMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 5), vm.state.value.visibleMonth)
    }

    @Test
    fun `past-sessions sheet toggles`() = runTest {
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-05-01T00:00:00Z"))
        val vm = vm(programs)
        advanceUntilIdle()

        vm.openPastSessions()
        assertTrue(vm.state.value.showPastSessions)
        vm.dismissPastSessions()
        assertFalse(vm.state.value.showPastSessions)
    }

    @Test
    fun `only the featured program's draft surfaces as the resume banner`() = runTest {
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-05-01T00:00:00Z"))
        drafts.value = listOf(
            ProgramFixtures.activeDraft.copy(programId = "other"),
            ProgramFixtures.activeDraft, // programId p1
        )
        val vm = vm(programs)
        advanceUntilIdle()

        assertEquals(ProgramFixtures.activeDraft, vm.state.value.activeDraft)

        drafts.value = emptyList()
        advanceUntilIdle()
        assertNull(vm.state.value.activeDraft)
    }

    @Test
    fun `restore success exposes the restored session until consumed`() = runTest {
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-05-01T00:00:00Z"))
        coEvery { sessionRepo.restoreParked("p1", "s2") } returns
            Result.success(ProgramFixtures.activeDraft)
        val vm = vm(programs)
        advanceUntilIdle()

        vm.restoreParked(ProgramFixtures.parkedCompletion)
        advanceUntilIdle()

        assertEquals(ProgramFixtures.parkedCompletion, vm.state.value.restoredSession)
        assertNull(vm.state.value.parkedError)

        vm.consumeRestoredSession()
        assertNull(vm.state.value.restoredSession)
    }
}
