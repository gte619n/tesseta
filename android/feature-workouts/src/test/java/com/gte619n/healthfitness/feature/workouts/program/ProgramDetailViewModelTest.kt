package com.gte619n.healthfitness.feature.workouts.program

import androidx.lifecycle.SavedStateHandle
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val handle = SavedStateHandle(mapOf(WorkoutsRoutes.ARG_PROGRAM_ID to "p1"))

    @Test
    fun `deep load populates program and parallel calendar populates this week`() = runTest {
        coEvery { repo.get("p1") } returns Result.success(ProgramFixtures.deepProgram)
        coEvery { repo.calendar(any(), any(), any()) } returns Result.success(ProgramFixtures.thisWeek)

        val vm = ProgramDetailViewModel(repo, handle)
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

        val vm = ProgramDetailViewModel(repo, handle)
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

        val vm = ProgramDetailViewModel(repo, handle)
        advanceUntilIdle()

        val state = vm.state.value
        assertNotNull(state.program)
        assertEquals(emptyList<Any>(), state.thisWeek)
        assertNull(state.error)
    }
}
