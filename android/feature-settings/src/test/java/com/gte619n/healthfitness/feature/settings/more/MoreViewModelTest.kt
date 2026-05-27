package com.gte619n.healthfitness.feature.settings.more

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.profile.Profile
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the More overflow screen's view-model with a fake profile
 * repository and a counted sign-out action. The view-model's only
 * branching logic is "profile fetch succeeds vs fails" + "sign-out
 * propagates to the action and then to the caller" — both covered
 * here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoreViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads profile on init then exposes Loaded`() = runTest(dispatcher) {
        val profile = Profile("u-1", "evan@oxos.com", "Evan Ruff", heightCm = 188)
        val vm = MoreViewModel(
            profileRepository = FakeProfileRepository(initial = profile),
            signOutAction = CountingSignOut(),
        )
        vm.state.test {
            assertEquals(MoreViewModel.UiState.Loading, awaitItem())
            val loaded = awaitItem()
            assertTrue(loaded is MoreViewModel.UiState.Loaded)
            assertEquals(profile, (loaded as MoreViewModel.UiState.Loaded).profile)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `get failure collapses to NoProfile rather than Error so menu rows still render`() = runTest(dispatcher) {
        val vm = MoreViewModel(
            profileRepository = FakeProfileRepository(initial = null),
            signOutAction = CountingSignOut(),
        )
        vm.state.test {
            assertEquals(MoreViewModel.UiState.Loading, awaitItem())
            assertEquals(MoreViewModel.UiState.NoProfile, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh re-runs the fetch and re-emits Loading then a terminal state`() = runTest(dispatcher) {
        val vm = MoreViewModel(
            profileRepository = FakeProfileRepository(initial = null),
            signOutAction = CountingSignOut(),
        )
        vm.state.test {
            assertEquals(MoreViewModel.UiState.Loading, awaitItem())
            assertEquals(MoreViewModel.UiState.NoProfile, awaitItem())
            vm.refresh()
            assertEquals(MoreViewModel.UiState.Loading, awaitItem())
            assertEquals(MoreViewModel.UiState.NoProfile, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signOut invokes the action then calls the onDone callback`() = runTest(dispatcher) {
        val signOut = CountingSignOut()
        val vm = MoreViewModel(
            profileRepository = FakeProfileRepository(initial = null),
            signOutAction = signOut,
        )
        var done = false
        vm.signOut { done = true }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, signOut.calls)
        assertTrue(done)
    }

    private class FakeProfileRepository(
        private val initial: Profile?,
    ) : ProfileRepository {
        override suspend fun get(): Result<Profile> =
            if (initial == null) Result.failure(RuntimeException("no profile"))
            else Result.success(initial)

        override suspend fun updateHeightCm(heightCm: Int?): Result<Profile> =
            Result.failure(UnsupportedOperationException("not used by MoreViewModel"))
    }

    private class CountingSignOut : SignOutAction {
        var calls = 0
        override suspend fun invoke() {
            calls += 1
        }
    }
}
