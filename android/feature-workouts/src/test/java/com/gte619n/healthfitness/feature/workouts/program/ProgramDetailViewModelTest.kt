package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.SavedStateHandle
import com.gte619n.healthfitness.domain.workouts.program.ProgramActivationInvalidException
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: WorkoutProgramRepository = mockk()
    private val sessionRepo: WorkoutSessionRepository = mockk()
    private val drafts = MutableStateFlow<List<WorkoutSessionDraft>>(emptyList())
    private val parked = MutableStateFlow<List<ParkedCompletion>>(emptyList())
    private val handle = SavedStateHandle(mapOf(WorkoutsRoutes.ARG_PROGRAM_ID to "p1"))

    private fun vm(): ProgramDetailViewModel {
        every { sessionRepo.observeDrafts() } returns drafts
        every { sessionRepo.observeParkedCompletions() } returns parked
        // Default: no program nutrition guidance (apply path covered separately).
        coEvery { repo.nutritionGuidance(any()) } returns Result.success(null)
        return ProgramDetailViewModel(repo, sessionRepo, handle)
    }

    @Test
    fun `deep load populates program and parallel calendar populates this week`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)

        val vm = vm()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertNotNull(state.program)
        assertEquals(3, state.program?.phases?.size)
        assertEquals(3, state.thisWeek.size)
        // Embedded exercise summary is carried on the prescription.
        val presc = state.program!!.phases.first().days.first().blocks.first().prescriptions.first()
        assertNotNull(presc.exercise)
        assertNull(state.error)
    }

    @Test
    fun `repo failure surfaces error`() = runTest {
        coEvery { repo.get("p1") } returns Result.failure(RuntimeException("nope"))
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(emptyList())

        val vm = vm()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.loading)
        assertEquals("nope", state.error)
        assertNull(state.program)
    }

    @Test
    fun `calendar failure still loads the program`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.failure(RuntimeException("x"))

        val vm = vm()
        advanceUntilIdle()

        val state = vm.state.value
        assertNotNull(state.program)
        assertEquals(emptyList<Any>(), state.thisWeek)
        assertNull(state.error)
    }

    @Test
    fun `in-flight draft for this program surfaces as activeDraft`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)
        drafts.value = listOf(
            ProgramFixtures.activeDraft.copy(programId = "other"),
            ProgramFixtures.activeDraft,
        )

        val vm = vm()
        advanceUntilIdle()

        assertEquals(ProgramFixtures.activeDraft, vm.state.value.activeDraft)

        // Banner disappears when the draft is finished/skipped/discarded.
        drafts.value = emptyList()
        advanceUntilIdle()
        assertNull(vm.state.value.activeDraft)
    }

    @Test
    fun `parked completion for this program surfaces for the recovery banner`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)
        parked.value = listOf(
            ProgramFixtures.parkedCompletion.copy(programId = "other"),
            ProgramFixtures.parkedCompletion,
        )

        val vm = vm()
        advanceUntilIdle()

        // Only THIS program's parked upload is offered on its detail.
        assertEquals(ProgramFixtures.parkedCompletion, vm.state.value.parkedCompletion)

        // Banner disappears once the row is restored or discarded.
        parked.value = emptyList()
        advanceUntilIdle()
        assertNull(vm.state.value.parkedCompletion)
    }

    @Test
    fun `restore success exposes the restored session until consumed`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)
        coEvery { sessionRepo.restoreParked("p1", "s2") } returns
            Result.success(ProgramFixtures.activeDraft)

        val vm = vm()
        advanceUntilIdle()
        vm.restoreParked(ProgramFixtures.parkedCompletion)
        advanceUntilIdle()

        assertEquals(ProgramFixtures.parkedCompletion, vm.state.value.restoredSession)
        assertNull(vm.state.value.parkedError)

        vm.consumeRestoredSession()
        assertNull(vm.state.value.restoredSession)
    }

    @Test
    fun `activation 422 surfaces the issue list inline, not a generic error`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)
        val issues = listOf("Day 'Lower' has no gym assigned.", "Program has no training days to schedule.")
        coEvery { repo.activate("p1") } returns
            Result.failure(ProgramActivationInvalidException(issues))

        val vm = vm()
        advanceUntilIdle()
        vm.activate()
        advanceUntilIdle()

        assertEquals(issues, vm.state.value.activationIssues)
        assertNull(vm.state.value.error)
        assertEquals(false, vm.state.value.loading)

        vm.dismissActivationIssues()
        assertEquals(emptyList<String>(), vm.state.value.activationIssues)
    }

    @Test
    fun `non-422 activation failure surfaces a generic error`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)
        coEvery { repo.activate("p1") } returns Result.failure(RuntimeException("offline"))

        val vm = vm()
        advanceUntilIdle()
        vm.activate()
        advanceUntilIdle()

        assertEquals("offline", vm.state.value.error)
        assertEquals(emptyList<String>(), vm.state.value.activationIssues)
    }

    @Test
    fun `saveEdit patches details and closes the sheet on success`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)
        val updated = ProgramFixtures.deepProgram.copy(title = "New title", description = "New desc")
        coEvery { repo.updateDetails("p1", "New title", "New desc") } returns Result.success(updated)

        val vm = vm()
        advanceUntilIdle()
        vm.startEdit()
        assertEquals(true, vm.state.value.editing)
        vm.saveEdit("New title", "New desc")
        advanceUntilIdle()

        assertEquals(false, vm.state.value.editing)
        assertEquals(false, vm.state.value.savingEdit)
        assertEquals("New title", vm.state.value.program?.title)
    }

    @Test
    fun `restore failure surfaces a banner-scoped error`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)
        coEvery { sessionRepo.restoreParked("p1", "s2") } returns
            Result.failure(RuntimeException("draft in flight"))

        val vm = vm()
        advanceUntilIdle()
        vm.restoreParked(ProgramFixtures.parkedCompletion)
        advanceUntilIdle()

        assertNull(vm.state.value.restoredSession)
        assertEquals("draft in flight", vm.state.value.parkedError)
        // The screen-level error stays untouched (the program is still rendered).
        assertNull(vm.state.value.error)
    }
}
