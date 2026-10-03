package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.Entry
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.shared.sync.MirrorTables
import com.gte619n.healthfitness.shared.sync.SqlDelightMirrorStore
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 (#2 follow-up) — nutrition day with an offline read-through cache.
 *
 * Delegates the full [NutritionDayRepository] surface to the networked [http] impl
 * (observeDay stays the authoritative, reactive network source — zero regression to
 * the working Today screen) and overrides only:
 *  - [cachedDay] → assembled from the on-device mirror (seeded below + by the sync
 *    delta pull), so a cold / offline start shows the last-known day instantly
 *    instead of null;
 *  - [day] → the network fetch, then best-effort SEED the mirror (entries keyed by
 *    plain `entryId`, matching the sync engine's keying so a later delta supersedes
 *    by LWW rather than duplicating) so the cache stays warm.
 *
 * Full reactive offline updates (observeDay off the mirror) + offline writes (outbox)
 * remain a device-verified follow-up; this is the safe, no-regression offline-read step.
 */
class MirrorNutritionDayRepository(
    private val http: HttpNutritionDayRepository,
    private val mirror: SqlDelightMirrorStore,
    private val json: Json = LENIENT,
) : NutritionDayRepository by http {

    override suspend fun cachedDay(date: String): NutritionDay? {
        val entries = mirror.activeRecords(MirrorTables.NUTRITION_ENTRIES)
            .mapNotNull { runCatching { json.decodeFromString(Entry.serializer(), it.payloadJson) }.getOrNull() }
        val target = mirror.activeRecords(MirrorTables.NUTRITION_TARGETS).firstOrNull()
            ?.let { runCatching { json.decodeFromString(Macros.serializer(), it.payloadJson) }.getOrNull() }
        val day = assembleNutritionDay(date, entries, target)
        // Nothing cached for this day → let the caller fall through to the network.
        return if (day.meals.isEmpty() && day.target == null) null else day
    }

    override suspend fun day(date: String): NutritionDay {
        val day = http.day(date)
        runCatching { seedMirror(date, day) }
        return day
    }

    /** Write the fetched day's entries + target into the mirror as clean (SYNCED)
     *  rows, keyed exactly as the sync engine keys them (entryId / a target singleton). */
    private fun seedMirror(date: String, day: NutritionDay) {
        val stamp = Clock.System.now().toString()
        day.meals.flatMap { it.entries }.forEach { entry ->
            val withDate = if (entry.date == null) entry.copy(date = date) else entry
            mirror.applyLocalSynced(
                collection = MirrorTables.NUTRITION_ENTRIES,
                id = entry.entryId,
                payloadJson = json.encodeToString(Entry.serializer(), withDate),
                lastUpdate = stamp,
            )
        }
        day.target?.let {
            mirror.applyLocalSynced(
                collection = MirrorTables.NUTRITION_TARGETS,
                id = "target",
                payloadJson = json.encodeToString(Macros.serializer(), it),
                lastUpdate = stamp,
            )
        }
    }

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    }
}
