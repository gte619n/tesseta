package com.gte619n.healthfitness.feature.settings.profile

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.profile.Profile
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun profile(heightCm: Int? = 180) = Profile(
        userId = "u1",
        email = "a@b.com",
        displayName = "Alice",
        heightCm = heightCm,
    )

    private class FakeProfileRepository(
        var getResult: Result<Profile>,
        var updateResult: (Int?) -> Result<Profile> = { Result.success(Profile("u1", null, null, it)) },
    ) : ProfileRepository {
        var lastUpdatedHeightCm: Int? = null
        override suspend fun get(): Result<Profile> = getResult
        override suspend fun updateHeightCm(heightCm: Int?): Result<Profile> {
            lastUpdatedHeightCm = heightCm
            return updateResult(heightCm)
        }
    }

    @Test
    fun loadingThenLoaded() = runTest {
        val repo = FakeProfileRepository(getResult = Result.success(profile()))
        val vm = ProfileViewModel(repo)

        vm.state.test {
            // init { refresh() } drives Loading -> Loaded on the unconfined
            // dispatcher; by collection time we may already be Loaded.
            val first = awaitItem()
            val loaded = if (first is ProfileViewModel.UiState.Loaded) first else awaitItem()
            assertTrue(loaded is ProfileViewModel.UiState.Loaded)
            assertEquals(180, (loaded as ProfileViewModel.UiState.Loaded).profile.heightCm)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun saveHeightConvertsFtInToCm() = runTest {
        val repo = FakeProfileRepository(
            getResult = Result.success(profile(heightCm = 180)),
            updateResult = { Result.success(Profile("u1", "a@b.com", "Alice", it)) },
        )
        val vm = ProfileViewModel(repo)

        vm.state.test {
            // drain to the initial Loaded state
            var current = awaitItem()
            while (current !is ProfileViewModel.UiState.Loaded) current = awaitItem()

            vm.saveHeight(6, 2)

            // Loaded(saving=true) then Loaded(heightCm=188)
            val saving = awaitItem()
            assertTrue((saving as ProfileViewModel.UiState.Loaded).saving)

            val done = awaitItem() as ProfileViewModel.UiState.Loaded
            assertEquals(188, done.profile.heightCm)
            assertEquals(188, repo.lastUpdatedHeightCm)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun repositoryFailureSurfacesError() = runTest {
        val repo = FakeProfileRepository(
            getResult = Result.failure(RuntimeException("boom")),
        )
        val vm = ProfileViewModel(repo)

        vm.state.test {
            var current = awaitItem()
            while (current !is ProfileViewModel.UiState.Error) current = awaitItem()
            assertEquals("boom", (current as ProfileViewModel.UiState.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
