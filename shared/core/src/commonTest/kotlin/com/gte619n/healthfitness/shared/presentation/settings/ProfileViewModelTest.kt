package com.gte619n.healthfitness.shared.presentation.settings

import app.cash.turbine.test
import com.gte619n.healthfitness.shared.data.ProfileRepository
import com.gte619n.healthfitness.shared.data.UnitPreferencesRepository
import com.gte619n.healthfitness.shared.domain.prefs.HeightUnit
import com.gte619n.healthfitness.shared.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.shared.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.shared.domain.prefs.WeightUnit
import com.gte619n.healthfitness.shared.domain.profile.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — shared ProfileViewModel test, ported from the
 * Android `feature-settings/.../profile/ProfileViewModelTest.kt` (MockK → hand
 * fakes so it runs in commonTest on JVM + iOS).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
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
        private val getResult: Result<Profile>,
        private val cachedProfile: Profile? = null,
        private val updateResult: (Int?) -> Result<Profile> =
            { Result.success(Profile("u1", null, null, it)) },
    ) : ProfileRepository {
        var lastUpdatedHeight: Int? = null
        override suspend fun cached(): Profile? = cachedProfile
        override suspend fun get(): Result<Profile> = getResult
        override suspend fun updateHeightCm(heightCm: Int?): Result<Profile> {
            lastUpdatedHeight = heightCm
            return updateResult(heightCm)
        }
        override suspend fun updateBiologicalSex(biologicalSex: String?) =
            getResult
        override suspend fun updateDateOfBirth(dateOfBirth: String?) =
            getResult
    }

    private class FakeUnitPreferencesRepository : UnitPreferencesRepository {
        val backing = MutableStateFlow(UnitPreferences())
        override val preferences: Flow<UnitPreferences> = backing
        override suspend fun setHeightUnit(unit: HeightUnit) {
            backing.value = backing.value.copy(height = unit)
        }
        override suspend fun setWeightUnit(unit: WeightUnit) = Unit
        override suspend fun setTemperatureUnit(unit: TemperatureUnit) = Unit
    }

    private fun viewModel(repo: ProfileRepository) =
        ProfileViewModel(repo, FakeUnitPreferencesRepository())

    @Test
    fun loadingThenLoaded() = runTest {
        val repo = FakeProfileRepository(getResult = Result.success(profile()))
        val vm = viewModel(repo)

        vm.state.test {
            val first = awaitItem()
            val loaded = if (first is ProfileViewModel.UiState.Loaded) first else awaitItem()
            assertTrue(loaded is ProfileViewModel.UiState.Loaded)
            assertEquals(180, loaded.profile.heightCm)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun saveHeightConvertsFtInToCm() = runTest {
        val repo = FakeProfileRepository(
            getResult = Result.success(profile(heightCm = 180)),
            updateResult = { Result.success(Profile("u1", "a@b.com", "Alice", it)) },
        )
        val vm = viewModel(repo)

        vm.state.test {
            var current = awaitItem()
            while (current !is ProfileViewModel.UiState.Loaded) current = awaitItem()

            vm.saveHeight(6, 2)

            val saving = awaitItem()
            assertTrue((saving as ProfileViewModel.UiState.Loaded).saving)

            val done = awaitItem() as ProfileViewModel.UiState.Loaded
            assertEquals(188, done.profile.heightCm)
            assertEquals(188, repo.lastUpdatedHeight)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun repositoryFailureSurfacesError() = runTest {
        val repo = FakeProfileRepository(
            getResult = Result.failure(RuntimeException("boom")),
        )
        val vm = viewModel(repo)

        vm.state.test {
            var current = awaitItem()
            while (current !is ProfileViewModel.UiState.Error) current = awaitItem()
            assertEquals("boom", current.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cachedSeedsBeforeNetwork() = runTest {
        // A cached profile seeds Loaded instantly; a failing network never bounces
        // the screen back to Error (offline-first invariant).
        val repo = FakeProfileRepository(
            getResult = Result.failure(RuntimeException("offline")),
            cachedProfile = profile(heightCm = 172),
        )
        val vm = viewModel(repo)

        vm.state.test {
            var current = awaitItem()
            while (current !is ProfileViewModel.UiState.Loaded) current = awaitItem()
            assertEquals(172, current.profile.heightCm)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
