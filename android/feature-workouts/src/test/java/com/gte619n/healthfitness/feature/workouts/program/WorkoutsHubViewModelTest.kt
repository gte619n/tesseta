package com.gte619n.healthfitness.feature.workouts.program

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.feature.workouts.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsHubViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: WorkoutSessionRepository = mockk()
    private val drafts = MutableStateFlow<List<WorkoutSessionDraft>>(emptyList())

    @Test
    fun `surfaces the newest draft for the resume banner`() = runTest {
        every { repo.observeDrafts() } returns drafts
        drafts.value = listOf(ProgramFixtures.activeDraft)
        val vm = WorkoutsHubViewModel(repo)

        vm.activeDraft.test {
            // stateIn's initial value, then the draft once collection starts.
            assertNull(awaitItem())
            assertEquals(ProgramFixtures.activeDraft, awaitItem())

            // The banner clears when the draft is finished/skipped/discarded.
            drafts.value = emptyList()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
