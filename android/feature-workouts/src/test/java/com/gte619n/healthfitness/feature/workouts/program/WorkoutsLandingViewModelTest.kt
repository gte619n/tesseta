package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.data.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.data.workouts.settings.WorkoutSettingsRepository
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val settingsRepo: WorkoutSettingsRepository = mockk(relaxed = true)
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
        weeklyTarget: Int = 4,
        // Completed sessions across ALL programs (the streak + compliance-grid
        // source). Defaults to the featured calendar, matching a single-program
        // history; pass explicitly to model a previous program's sessions.
        allCompleted: List<ScheduledWorkout>? = null,
    ): WorkoutsLandingViewModel {
        every { sessionRepo.observeDrafts() } returns drafts
        every { sessionRepo.observeParkedCompletions() } returns parked
        every { repo.observePrograms() } returns flowOf(programs)
        every { repo.observeProgram(any()) } answers {
            val id = firstArg<String>()
            flowOf(programs.firstOrNull { it.programId == id })
        }
        every { repo.observeCalendar(any(), any(), any()) } returns flowOf(calendar)
        every { repo.observeAllCompleted(any(), any()) } returns flowOf(allCompleted ?: calendar)
        // Cross-program heatmap (incl. archived) — default empty; the Room-backed
        // paths cover these fixtures. Its own test stubs it explicitly.
        coEvery { repo.completedWorkoutDays() } returns emptySet()
        // Lazy auto-continue hook — default to a no-op (no sessions appended) so
        // the existing fixtures are unaffected; the auto-continue tests override it.
        coEvery { repo.ensureUpcoming(any()) } returns Result.success(emptyList())
        every { settingsRepo.weeklyStreakTarget } returns flowOf(weeklyTarget)
        return WorkoutsLandingViewModel(repo, sessionRepo, settingsRepo).also { it.today = today }
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
    fun `slices this week, month, past and weekly streak from one calendar`() = runTest {
        val calendar = listOf(
            sched("2026-05-29", ScheduledStatus.COMPLETED), // week -2 (05-25..05-31): 1 → short
            sched("2026-06-01", ScheduledStatus.COMPLETED), // week -1 (06-01..06-07)
            sched("2026-06-03", ScheduledStatus.COMPLETED), // week -1
            sched("2026-06-08", ScheduledStatus.COMPLETED), // this week (Mon)
            sched("2026-06-10", ScheduledStatus.COMPLETED), // this week (today)
            sched("2026-06-12", ScheduledStatus.PLANNED), // this week (future, not completed)
        )
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-05-01T00:00:00Z"))
        val vm = vm(programs, calendar, weeklyTarget = 2)
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
        // Current week (2 completed) + week -1 (2 completed) meet the target of 2;
        // week -2 has only one, ending the streak at two weeks.
        assertEquals(2, state.weekStreak)
        assertEquals(2, state.completedThisWeek)
        assertEquals(2, state.weeklyStreakTarget)
        assertEquals(YearMonth.of(2026, 6), state.visibleMonth)
    }

    @Test
    fun `paging back before the featured program shows the previous program's completions`() = runTest {
        // Featured program only has June sessions; May's workouts belong to an
        // earlier (archived) program and arrive via the cross-program completed
        // read. Paging back to May must still light those days up.
        val calendar = listOf(
            sched("2026-06-08", ScheduledStatus.COMPLETED),
            sched("2026-06-10", ScheduledStatus.PLANNED),
        )
        val oldProgram = listOf(
            sched("2026-05-05", ScheduledStatus.COMPLETED).copy(scheduledId = "old-05-05", programId = "old"),
            sched("2026-05-07", ScheduledStatus.COMPLETED).copy(scheduledId = "old-05-07", programId = "old"),
        )
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-06-01T00:00:00Z"))
        val vm = vm(
            programs,
            calendar,
            allCompleted = oldProgram + calendar.filter { it.status == ScheduledStatus.COMPLETED },
        )
        advanceUntilIdle()

        // Current month: featured sessions only, no duplicates from the union.
        assertEquals(
            listOf("2026-06-08", "2026-06-10"),
            vm.state.value.monthDays.map { it.date.toString() }.sorted(),
        )

        vm.prevMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 5), vm.state.value.visibleMonth)
        assertEquals(
            listOf("2026-05-05", "2026-05-07"),
            vm.state.value.monthDays.map { it.date.toString() }.sorted(),
        )
    }

    @Test
    fun `cross-program heatmap days light up a month the featured program never covered`() = runTest {
        // Archived-program months aren't in the featured calendar or the mirror, so
        // only the server heatmap (completedWorkoutDays) carries them. Paging to that
        // month must still show those days as completed.
        val calendar = listOf(sched("2026-06-08", ScheduledStatus.COMPLETED))
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-06-01T00:00:00Z"))
        every { repo.observePrograms() } returns flowOf(programs)
        every { repo.observeProgram(any()) } answers {
            flowOf(programs.firstOrNull { it.programId == firstArg<String>() })
        }
        every { sessionRepo.observeDrafts() } returns drafts
        every { sessionRepo.observeParkedCompletions() } returns parked
        every { repo.observeCalendar(any(), any(), any()) } returns flowOf(calendar)
        every { repo.observeAllCompleted(any(), any()) } returns flowOf(calendar)
        every { settingsRepo.weeklyStreakTarget } returns flowOf(4)
        coEvery { repo.completedWorkoutDays() } returns setOf(
            LocalDate.parse("2026-05-05"),
            LocalDate.parse("2026-05-07"),
        )
        coEvery { repo.ensureUpcoming(any()) } returns Result.success(emptyList())
        val vm = WorkoutsLandingViewModel(repo, sessionRepo, settingsRepo).also { it.today = today }
        advanceUntilIdle()

        vm.prevMonth()
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 5), vm.state.value.visibleMonth)
        // The grid derives from monthDays + completedDates; May's completed cells
        // come purely from the heatmap set.
        val grid = complianceGrid(
            vm.state.value.monthDays,
            vm.state.value.today,
            extraCompletedDates = vm.state.value.completedDates,
        )
        assertEquals(ComplianceCellKind.COMPLETED, grid[LocalDate.parse("2026-05-05")])
        assertEquals(ComplianceCellKind.COMPLETED, grid[LocalDate.parse("2026-05-07")])
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

    @Test
    fun `auto-continues an ACTIVE program that has run out of upcoming sessions`() = runTest {
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-05-01T00:00:00Z"))
        // Only a past completed session — nothing upcoming.
        val calendar = listOf(sched("2026-06-03", ScheduledStatus.COMPLETED))
        val vm = vm(programs, calendar)
        coEvery { repo.ensureUpcoming("p1") } returns Result.success(
            listOf(sched("2026-06-15", ScheduledStatus.PLANNED)),
        )
        advanceUntilIdle()

        // Fired once (guarded) to extend the program in place.
        coVerify(exactly = 1) { repo.ensureUpcoming("p1") }
    }

    @Test
    fun `does not auto-continue when a future session already exists`() = runTest {
        val programs = listOf(program("p1", ProgramStatus.ACTIVE, "2026-05-01T00:00:00Z"))
        val calendar = listOf(
            sched("2026-06-03", ScheduledStatus.COMPLETED),
            sched("2026-06-15", ScheduledStatus.PLANNED), // upcoming — no dead end
        )
        val vm = vm(programs, calendar)
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.ensureUpcoming(any()) }
    }

    @Test
    fun `does not auto-continue a non-active featured program`() = runTest {
        // A COMPLETED program surfaced as the fallback featured one must not be
        // silently resurrected — only the program the user is actively following.
        val programs = listOf(program("done", ProgramStatus.COMPLETED, "2026-05-01T00:00:00Z"))
        val vm = vm(programs)
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.ensureUpcoming(any()) }
    }
}
