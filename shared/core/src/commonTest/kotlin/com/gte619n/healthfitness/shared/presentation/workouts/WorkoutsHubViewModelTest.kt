package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave D — port of the Android `WorkoutsLandingViewModelTest`
 * intent. Verifies the reactive featured-program resolution, client-side
 * compliance/streak derivation ([ComplianceMath]), and the empty state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsHubViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    // A Wednesday, so "this week" is Mon 2026-09-21 .. Sun 2026-09-27.
    private val today = LocalDate(2026, 9, 23)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        repo: FakeWorkoutProgramRepository = FakeWorkoutProgramRepository(),
        session: FakeWorkoutSessionRepository = FakeWorkoutSessionRepository(),
        settings: FakeWorkoutSettingsRepository = FakeWorkoutSettingsRepository(),
    ) = WorkoutsHubViewModel(repo, session, settings, today = today)

    @Test
    fun featuresActiveProgramAndDerivesThisWeek() = runTest {
        val repo = FakeWorkoutProgramRepository()
        repo.setCalendar(
            listOf(
                sampleScheduled("s1", LocalDate(2026, 9, 21), ScheduledStatus.COMPLETED),
                sampleScheduled("s2", LocalDate(2026, 9, 23), ScheduledStatus.PLANNED),
                sampleScheduled("s3", LocalDate(2026, 9, 14), ScheduledStatus.COMPLETED), // last week
            ),
        )
        val vm = vm(repo)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals("prog-1", s.program?.programId)
        assertTrue(s.hasAnyProgram)
        // This week has Mon (completed) + Wed (planned).
        assertEquals(2, s.thisWeek.size)
        // One completed workout so far this week.
        assertEquals(1, s.completedThisWeek)
    }

    @Test
    fun computesWeeklyStreakFromCompletedAcrossPrograms() = runTest {
        val repo = FakeWorkoutProgramRepository()
        // Target defaults to 3/week. Give the current + two prior weeks 3 each.
        val completed = buildList {
            // current week (Mon/Tue/Wed on/before today)
            add(sampleScheduled("c1", LocalDate(2026, 9, 21)))
            add(sampleScheduled("c2", LocalDate(2026, 9, 22)))
            add(sampleScheduled("c3", LocalDate(2026, 9, 23)))
            // prior week Mon 09-14
            add(sampleScheduled("p1", LocalDate(2026, 9, 14)))
            add(sampleScheduled("p2", LocalDate(2026, 9, 15)))
            add(sampleScheduled("p3", LocalDate(2026, 9, 16)))
            // two weeks ago Mon 09-07
            add(sampleScheduled("q1", LocalDate(2026, 9, 7)))
            add(sampleScheduled("q2", LocalDate(2026, 9, 8)))
            add(sampleScheduled("q3", LocalDate(2026, 9, 9)))
        }
        repo.setAllCompleted(completed)
        val vm = vm(repo)
        advanceUntilIdle()

        assertEquals(3, vm.state.value.weekStreak)
    }

    @Test
    fun noProgramsShowsEmptyState() = runTest {
        val repo = FakeWorkoutProgramRepository(programs = MutableStateFlow(emptyList()))
        val vm = vm(repo)
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(null, s.program)
        assertFalse(s.hasAnyProgram)
    }

    @Test
    fun fallsBackToMostRecentlyTouchedWhenNoneActive() = runTest {
        val older = sampleProgram(
            programId = "old", status = ProgramStatus.COMPLETED,
            updatedAt = kotlinx.datetime.Instant.fromEpochSeconds(1_000),
        )
        val newer = sampleProgram(
            programId = "new", status = ProgramStatus.DRAFT,
            updatedAt = kotlinx.datetime.Instant.fromEpochSeconds(2_000),
        )
        val repo = FakeWorkoutProgramRepository(programs = MutableStateFlow(listOf(older, newer)))
        repo.programFlow("new").value = newer
        val vm = vm(repo)
        advanceUntilIdle()

        assertEquals("new", vm.state.value.program?.programId)
    }

    @Test
    fun draftBannerSurfacesForFeaturedProgram() = runTest {
        val repo = FakeWorkoutProgramRepository()
        val session = FakeWorkoutSessionRepository()
        val vm = vm(repo, session)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.activeDraft)

        session.drafts.value = listOf(
            WorkoutSessionDraft(
                programId = "prog-1",
                scheduledId = "sched-1",
                startedAt = kotlinx.datetime.Instant.fromEpochSeconds(1),
                lastActivityAt = kotlinx.datetime.Instant.fromEpochSeconds(2),
                status = com.gte619n.healthfitness.shared.domain.workouts.session.DraftStatus.ACTIVE,
                scheduled = sampleScheduled("sched-1", today, ScheduledStatus.PLANNED),
                logged = emptyMap(),
            ),
        )
        advanceUntilIdle()
        assertEquals("sched-1", vm.state.value.activeDraft?.scheduledId)
    }
}
