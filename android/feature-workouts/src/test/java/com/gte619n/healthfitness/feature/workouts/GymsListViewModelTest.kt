package com.gte619n.healthfitness.feature.workouts

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.feature.workouts.list.GymsListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Drives [GymsListViewModel] against a fake repository.
 *
 *  - Initial load → success → state.loading flips false, sort order
 *    puts default-gym first then alphabetical by name.
 *  - Initial load → failure → state.error surfaces.
 *  - refresh() re-invokes the repo and clears prior error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GymsListViewModelTest {

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
    fun `success populates list sorted with default first then alphabetical`() = runTest(dispatcher) {
        val repo = FakeLocationRepository(
            initial = Result.success(
                listOf(
                    location("loc_1", "Zebra Gym", isDefault = false),
                    location("loc_2", "Apex Fitness", isDefault = true),
                    location("loc_3", "Beta Box", isDefault = false),
                ),
            ),
        )
        val vm = GymsListViewModel(repo)
        vm.state.test(timeout = 5.seconds) {
            // The init block schedules a refresh; first emission may
            // still be the default loading state. We drain until we
            // see the loaded one.
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertFalse(s.loading)
            assertEquals(3, s.locations.size)
            assertEquals("Apex Fitness", s.locations[0].name)
            assertEquals("Beta Box", s.locations[1].name)
            assertEquals("Zebra Gym", s.locations[2].name)
            assertNull(s.error)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `failure surfaces error message`() = runTest(dispatcher) {
        val repo = FakeLocationRepository(initial = Result.failure(IOException("boom")))
        val vm = GymsListViewModel(repo)
        vm.state.test(timeout = 5.seconds) {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertFalse(s.loading)
            assertNotNull(s.error)
            assertEquals(emptyList<Location>(), s.locations)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `refresh clears prior error and re-fetches`() = runTest(dispatcher) {
        val repo = FakeLocationRepository(initial = Result.failure(IOException("boom")))
        val vm = GymsListViewModel(repo)
        vm.state.test(timeout = 5.seconds) {
            var s = awaitItem()
            while (s.loading) s = awaitItem()
            assertNotNull(s.error)

            repo.nextResult = Result.success(
                listOf(location("loc_1", "Home Gym", isDefault = true)),
            )
            vm.refresh()
            // Drain loading->loaded transitions
            s = awaitItem()
            while (s.loading) s = awaitItem()
            assertEquals(1, s.locations.size)
            assertNull(s.error)
            cancelAndConsumeRemainingEvents()
        }
    }

    private fun location(id: String, name: String, isDefault: Boolean): Location = Location(
        locationId = id,
        name = name,
        address = null,
        coverPhotoUrl = null,
        is24Hours = true,
        hours = null,
        amenities = emptyList(),
        equipmentIds = emptyList(),
        equipmentSpecs = emptyMap(),
        isDefault = isDefault,
        isActive = true,
        createdAt = Instant.parse("2026-05-26T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-26T00:00:00Z"),
    )
}

private class FakeLocationRepository(initial: Result<List<Location>>) : LocationRepository {
    var nextResult: Result<List<Location>> = initial

    override suspend fun list(includeInactive: Boolean): Result<List<Location>> = nextResult
    override suspend fun get(locationId: String): Result<Location> = error("not used")
    override suspend fun create(req: com.gte619n.healthfitness.domain.workouts.CreateLocationRequest): Result<Location> = error("not used")
    override suspend fun update(locationId: String, req: com.gte619n.healthfitness.domain.workouts.UpdateLocationRequest): Result<Location> = error("not used")
    override suspend fun delete(locationId: String): Result<Unit> = error("not used")
    override suspend fun setDefault(locationId: String): Result<Unit> = error("not used")
    override suspend fun uploadCoverPhoto(
        locationId: String,
        filename: String,
        mimeType: String,
        source: () -> InputStream,
    ): Result<Location> = error("not used")
    override suspend fun deleteCoverPhoto(locationId: String): Result<Location> = error("not used")
    override suspend fun updateEquipmentSpecs(
        locationId: String,
        equipmentId: String,
        specs: Map<String, Any?>,
    ): Result<Location> = error("not used")
}
