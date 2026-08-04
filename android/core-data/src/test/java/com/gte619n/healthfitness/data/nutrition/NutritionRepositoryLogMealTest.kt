package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.NutritionEntryEntity
import com.gte619n.healthfitness.data.db.entity.NutritionTargetEntity
import com.gte619n.healthfitness.data.db.dao.NutritionEntryDao
import com.gte619n.healthfitness.data.db.dao.NutritionTargetDao
import com.gte619n.healthfitness.data.sync.DrainTrigger
import com.gte619n.healthfitness.data.sync.FakeMirrorOps
import com.gte619n.healthfitness.data.sync.FakeOutboxDao
import com.gte619n.healthfitness.data.sync.KillSwitchGate
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.data.sync.MirrorRowData
import com.gte619n.healthfitness.data.sync.OutboxRepository
import com.gte619n.healthfitness.data.sync.SyncTestMoshi
import com.gte619n.healthfitness.data.sync.fakeDeviceIdProvider
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.MealGroup
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Logging a saved meal must land its row WITH the meal's already-generated plated
 * photo, not a picture-less placeholder that only fills in after a manual refresh.
 *
 * The server reuses the saved meal's READY photo, but the fresh day pull can
 * briefly return the new entry without it (the reuse attach and this date's read
 * aren't ordered), and the entry is neither ANALYZING nor image-PENDING — so
 * nothing (settle-poll or day() re-fetch) converges it. logDescribedMeal overlays
 * the client-known READY image (the one the add-sheet search row shows) so the
 * picture appears with the row.
 */
class NutritionRepositoryLogMealTest {

    private val date = "2026-08-03"
    private val api = mockk<NutritionApi>(relaxed = true)
    private lateinit var mirror: FakeMirrorOps
    private lateinit var repository: NutritionRepository

    @Before
    fun setUp() {
        mirror = FakeMirrorOps()
        val outbox = OutboxRepository(
            outboxDao = FakeOutboxDao(),
            mirror = mirror,
            replay = mockk(relaxed = true),
            deviceIdProvider = fakeDeviceIdProvider("device-A"),
            diagnostics = com.gte619n.healthfitness.data.sync.SyncDiagnostics(),
            io = Dispatchers.Unconfined,
            clock = { 1_000L },
        )
        val support = MirrorRepositorySupport(
            mirror = mirror,
            outbox = outbox,
            killSwitch = KillSwitchGate { false },
            drainTrigger = DrainTrigger { },
        )
        repository = NutritionRepository(
            api = api,
            entryDao = FakeNutritionEntryDao(mirror),
            targetDao = FakeNutritionTargetDao(mirror),
            support = support,
            moshi = SyncTestMoshi.instance,
        )
    }

    private fun entry(imageUrl: String?, imageStatus: String) = Entry(
        entryId = "e1",
        meal = "dinner",
        foodName = "Salmon, broccolini and potatoes",
        quantity = 1.0,
        macros = Macros(caloriesKcal = 492.0),
        source = "MANUAL",
        imageUrl = imageUrl,
        imageStatus = imageStatus,
    )

    private fun dayWith(e: Entry) = NutritionDay(
        date = date,
        totals = e.macros,
        meals = listOf(MealGroup(meal = "dinner", subtotal = e.macros, entries = listOf(e))),
    )

    private fun loggedEntry(): Entry {
        val meals = runBlocking { repository.day(date) }.meals
        return meals.single().entries.single()
    }

    @Test
    fun log_overlaysKnownReadyPhoto_whenThePullLacksIt() = runBlocking {
        // POST returns the pre-image entry; the day pull also lacks the photo.
        coEvery { api.logDescribedMeal(date, any()) } returns entry(null, "NONE")
        coEvery { api.getDay(date) } returns dayWith(entry(null, "NONE"))

        repository.logDescribedMeal(
            date, "meal-1", "dinner",
            knownImageUrl = "http://img/salmon.png",
            knownImageStatus = "READY",
        )

        val logged = loggedEntry()
        assertEquals("READY", logged.imageStatus)
        assertEquals("http://img/salmon.png", logged.imageUrl)
    }

    @Test
    fun log_keepsServerPhoto_whenThePullAlreadyCarriesIt() = runBlocking {
        // The pull already has the reused photo — the overlay must not clobber it.
        coEvery { api.logDescribedMeal(date, any()) } returns entry(null, "NONE")
        coEvery { api.getDay(date) } returns dayWith(entry("http://server/salmon.png", "READY"))

        repository.logDescribedMeal(
            date, "meal-1", "dinner",
            knownImageUrl = "http://img/salmon.png",
            knownImageStatus = "READY",
        )

        val logged = loggedEntry()
        assertEquals("READY", logged.imageStatus)
        assertEquals("http://server/salmon.png", logged.imageUrl)
    }

    @Test
    fun log_leavesGeneratingPhotoAlone_whenTheSavedMealHasNoReadyPhotoYet() = runBlocking {
        // A brand-new saved meal generating its first photo lands PENDING; with no
        // known-READY image the settle-poll owns it, so we must not force anything.
        coEvery { api.logDescribedMeal(date, any()) } returns entry(null, "NONE")
        coEvery { api.getDay(date) } returns dayWith(entry(null, "PENDING"))

        repository.logDescribedMeal(
            date, "meal-1", "dinner",
            knownImageUrl = null,
            knownImageStatus = "NONE",
        )

        assertEquals("PENDING", loggedEntry().imageStatus)
    }
}

private class FakeNutritionEntryDao(private val mirror: FakeMirrorOps) : NutritionEntryDao {
    private fun rows() = mirror.rows.entries
        .filter { it.key.startsWith("${MirrorTables.NUTRITION_ENTRIES}:") }
        .map { it.value }
        .map { NutritionEntryEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }

    override fun observeActive(): Flow<List<NutritionEntryEntity>> =
        MutableStateFlow(rows().filter { it.status != "ARCHIVED" }.sortedByDescending { it.lastUpdate })

    override suspend fun getById(id: String): NutritionEntryEntity? = rows().firstOrNull { it.id == id }

    override suspend fun upsert(row: NutritionEntryEntity) = mirror.upsert(
        MirrorTables.NUTRITION_ENTRIES,
        MirrorRowData(row.id, row.payloadJson, row.lastUpdate, row.status, row.dirty, row.syncState),
    )

    override suspend fun upsertAll(rows: List<NutritionEntryEntity>) { rows.forEach { upsert(it) } }

    override suspend fun markArchived(id: String, lastUpdate: Long) =
        mirror.markArchived(MirrorTables.NUTRITION_ENTRIES, id, lastUpdate)

    override suspend fun delete(id: String) = mirror.delete(MirrorTables.NUTRITION_ENTRIES, id)
}

private class FakeNutritionTargetDao(private val mirror: FakeMirrorOps) : NutritionTargetDao {
    private fun rows() = mirror.rows.entries
        .filter { it.key.startsWith("${MirrorTables.NUTRITION_TARGETS}:") }
        .map { it.value }
        .map { NutritionTargetEntity(it.id, it.payloadJson, it.lastUpdate, it.status, it.dirty, it.syncState) }

    override fun observeActive(): Flow<List<NutritionTargetEntity>> =
        MutableStateFlow(rows().filter { it.status != "ARCHIVED" }.sortedByDescending { it.lastUpdate })

    override suspend fun getById(id: String): NutritionTargetEntity? = rows().firstOrNull { it.id == id }

    override suspend fun upsert(row: NutritionTargetEntity) = mirror.upsert(
        MirrorTables.NUTRITION_TARGETS,
        MirrorRowData(row.id, row.payloadJson, row.lastUpdate, row.status, row.dirty, row.syncState),
    )

    override suspend fun upsertAll(rows: List<NutritionTargetEntity>) { rows.forEach { upsert(it) } }

    override suspend fun markArchived(id: String, lastUpdate: Long) =
        mirror.markArchived(MirrorTables.NUTRITION_TARGETS, id, lastUpdate)

    override suspend fun delete(id: String) = mirror.delete(MirrorTables.NUTRITION_TARGETS, id)
}
