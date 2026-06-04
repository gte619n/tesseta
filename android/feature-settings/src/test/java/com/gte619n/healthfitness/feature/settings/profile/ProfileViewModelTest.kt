package com.gte619n.healthfitness.feature.settings.profile

import app.cash.turbine.test
import com.gte619n.healthfitness.data.profile.ProfileRepository
import com.gte619n.healthfitness.domain.profile.Profile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

    // ProfileRepository is a concrete @Inject class; MockK mocks it directly.
    private fun fakeProfileRepository(
        getResult: Result<Profile>,
        updateResult: (Int?) -> Result<Profile> = { Result.success(Profile("u1", null, null, it)) },
    ): ProfileRepository = mockk {
        coEvery { get() } returns getResult
        coEvery { updateHeightCm(any()) } answers { updateResult(firstArg()) }
    }

    private class FakeUnitPreferencesRepository :
        com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository {
        override val preferences =
            kotlinx.coroutines.flow.MutableStateFlow(
                com.gte619n.healthfitness.domain.prefs.UnitPreferences(),
            )
        override suspend fun setHeightUnit(unit: com.gte619n.healthfitness.domain.prefs.HeightUnit) {
            preferences.value = preferences.value.copy(height = unit)
        }
        override suspend fun setWeightUnit(unit: com.gte619n.healthfitness.domain.prefs.WeightUnit) = Unit
        override suspend fun setTemperatureUnit(
            unit: com.gte619n.healthfitness.domain.prefs.TemperatureUnit,
        ) = Unit
    }

    private fun viewModel(repo: ProfileRepository) =
        ProfileViewModel(repo, FakeUnitPreferencesRepository())

    @Test
    fun loadingThenLoaded() = runTest {
        val repo = fakeProfileRepository(getResult = Result.success(profile()))
        val vm = viewModel(repo)

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
        val repo = fakeProfileRepository(
            getResult = Result.success(profile(heightCm = 180)),
            updateResult = { Result.success(Profile("u1", "a@b.com", "Alice", it)) },
        )
        val vm = viewModel(repo)

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
            coVerify { repo.updateHeightCm(188) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun repositoryFailureSurfacesError() = runTest {
        val repo = fakeProfileRepository(
            getResult = Result.failure(RuntimeException("boom")),
        )
        val vm = viewModel(repo)

        vm.state.test {
            var current = awaitItem()
            while (current !is ProfileViewModel.UiState.Error) current = awaitItem()
            assertEquals("boom", (current as ProfileViewModel.UiState.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
